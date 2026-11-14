package app.offlinefin.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.offlinefin.core.AppError
import app.offlinefin.data.servers.Authenticator
import app.offlinefin.data.servers.CertificateProber
import app.offlinefin.data.servers.Server
import app.offlinefin.data.servers.ServerCatalog
import app.offlinefin.data.servers.ServerConnections
import app.offlinefin.data.servers.ServerNetDraft
import app.offlinefin.net.fingerprintForDisplay
import app.offlinefin.net.validateCustomHeader
import app.offlinefin.ui.CertificateSummary
import app.offlinefin.ui.ClientCertChooser
import app.offlinefin.ui.ConnectionView
import app.offlinefin.ui.onboarding.AddressChecker
import app.offlinefin.ui.onboarding.Check
import app.offlinefin.ui.toSummary
import app.offlinefin.ui.toView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AddressSlot { Home, Away }

/** A custom header. Values are secrets (proxy tokens), so [toString] hides everything. */
data class HeaderItem(val name: String, val value: String) {
    override fun toString() = "HeaderItem(<redacted>)"
}

/** A sub-page or dialog opened from the connection settings screen. */
sealed interface EditPane {
    /** Editing an address; the checks run through [ServerEditUiState.addressCheck]. */
    data class Address(val slot: AddressSlot, val text: String) : EditPane {
        override fun toString() = "Address(<redacted>)"
    }

    /** The warning shown before plain http is switched on. */
    data object HttpWarning : EditPane

    /** Reviewing a changed or untrusted certificate; trusting is always an explicit button. */
    data class CertificateReview(val loading: Boolean, val certificate: CertificateSummary?, val error: String?) : EditPane

    /** Adding ([originalName] null) or editing a header. [error] is the data layer's message and never contains the value. */
    data class Header(val originalName: String?, val name: String, val value: String, val error: String?) : EditPane {
        override fun toString() = "Header(<redacted>)"
    }
}

data class ServerEditUiState(
    val loaded: Boolean = false,
    /** The server was removed while the screen was open. */
    val missing: Boolean = false,
    val name: String = "",
    val homeAddress: String? = null,
    val awayAddress: String? = null,
    val allowCleartext: Boolean = false,
    val pinFingerprint: String? = null,
    /** The certificate is no longer the one that was trusted (changed or damaged pin). */
    val certificateChanged: Boolean = false,
    /** The certificate must be reviewed (changed, or not trusted). */
    val certificateNeedsReview: Boolean = false,
    val hasClientCertificate: Boolean = false,
    val headers: List<HeaderItem> = emptyList(),
    val connection: ConnectionView? = null,
    val pane: EditPane? = null,
    val addressCheck: Check = Check.Idle,
    val busy: Boolean = false,
    val error: String? = null,
) {
    /** Addresses and headers never appear. */
    override fun toString() = "ServerEditUiState(loaded=$loaded, pane=${pane?.let { it::class.simpleName }})"
}

/**
 * One server's connection settings. Every change goes through the same checks as adding a server (an edited
 * address must pass the checker and be the same server), turning plain http on is behind its warning, and a
 * changed certificate is only ever re-trusted by an explicit button after the user has seen its fingerprint.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerEditViewModel(
    private val serverId: String,
    private val catalog: ServerCatalog,
    auth: Authenticator,
    private val prober: CertificateProber,
    private val connections: ServerConnections,
) : ViewModel() {
    private data class Local(val pane: EditPane? = null, val busy: Boolean = false, val error: String? = null)

    private val local = MutableStateFlow(Local())
    private val checker = AddressChecker(auth, prober, viewModelScope, ::addressVerified)

    private val serverWithConnection = catalog.servers
        .map { servers -> servers.firstOrNull { it.id == serverId } }
        .flatMapLatest { server ->
            if (server == null) flowOf(null to null) else connections.connection(server).map { server to it.toView() }
        }

    val state: StateFlow<ServerEditUiState> =
        combine(serverWithConnection, local, checker.state) { (server, connection), local, check ->
            if (server == null) {
                ServerEditUiState(loaded = true, missing = true)
            } else {
                ServerEditUiState(
                    loaded = true,
                    name = server.name,
                    homeAddress = server.homeAddress?.value,
                    awayAddress = server.awayAddress?.value,
                    allowCleartext = server.allowCleartext,
                    pinFingerprint = server.pinnedSpkiSha256?.let(::fingerprintForDisplay),
                    certificateChanged = server.pinInvalid || connection?.certificateChanged == true,
                    certificateNeedsReview = server.pinInvalid || connection?.needsCertificateReview == true,
                    hasClientCertificate = server.clientCertAlias != null,
                    headers = server.customHeaders.entries.sortedBy { it.key.lowercase() }.map { HeaderItem(it.key, it.value) },
                    connection = connection,
                    pane = local.pane, addressCheck = check, busy = local.busy, error = local.error,
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ServerEditUiState())

    fun retry() = connections.refresh()
    fun dismissError() = local.update { it.copy(error = null) }

    fun dismissPane() {
        checker.cancel()
        local.update { it.copy(pane = null) }
    }

    // --- addresses ----------------------------------------------------------------------------------------------

    fun editAddress(slot: AddressSlot) {
        viewModelScope.launch {
            val server = catalog.server(serverId) ?: return@launch
            val current = if (slot == AddressSlot.Home) server.homeAddress else server.awayAddress
            local.update { it.copy(pane = EditPane.Address(slot, current?.value.orEmpty()), error = null) }
        }
    }

    fun onAddressText(text: String) {
        local.update { l -> (l.pane as? EditPane.Address)?.let { l.copy(pane = it.copy(text = text)) } ?: l }
        if (checker.state.value is Check.Failed) checker.cancel()
    }

    /** Blank removes the address (if another remains); anything else is checked first and saved only if it passes. */
    fun saveAddress() {
        val pane = local.value.pane as? EditPane.Address ?: return
        viewModelScope.launch {
            val server = catalog.server(serverId) ?: return@launch
            val text = pane.text.trim()
            if (text.isEmpty()) {
                val other = if (pane.slot == AddressSlot.Home) server.awayAddress else server.homeAddress
                if (other == null) {
                    local.update { it.copy(error = AppError(AppError.Kind.UNKNOWN, "A server needs at least one address.").userMessage) }
                } else {
                    save { it.withAddress(pane.slot, null) }
                }
            } else {
                checker.start(text, ServerNetDraft(server.allowCleartext, server.pinnedSpkiSha256, server.clientCertAlias, server.customHeaders), server.id)
            }
        }
    }

    /** Back from a warning or error to the address field, keeping what was typed. */
    fun cancelAddressCheck() = checker.cancel()

    fun offerHttp() = checker.offerHttp()
    fun allowHttpForAddress() = checker.allowHttp()
    fun trustAddressCertificate() = checker.trustCertificate()

    suspend fun chooseClientCertificateForAddress(chooser: ClientCertChooser) {
        chooser.choose()?.let(checker::useClientCertificate)
    }

    private suspend fun addressVerified(result: Check.Verified) {
        val pane = local.value.pane as? EditPane.Address ?: return
        val draft = result.draft
        save {
            it.withAddress(pane.slot, result.info.address).copy(
                allowCleartext = draft.allowCleartext,
                pinnedSpkiSha256 = draft.pinnedSpkiSha256,
                clientCertAlias = draft.clientCertAlias,
                pinInvalid = it.pinInvalid && draft.pinnedSpkiSha256 == null,
            )
        }
    }

    private fun Server.withAddress(slot: AddressSlot, address: app.offlinefin.net.ServerAddress?) =
        if (slot == AddressSlot.Home) copy(homeAddress = address) else copy(awayAddress = address)

    // --- plain http ---------------------------------------------------------------------------------------------

    /** Turning it on shows the warning first; turning it off is immediate. */
    fun setCleartext(on: Boolean) {
        if (on) {
            local.update { it.copy(pane = EditPane.HttpWarning, error = null) }
        } else {
            viewModelScope.launch { save { it.copy(allowCleartext = false) } }
        }
    }

    fun confirmCleartext() {
        if (local.value.pane != EditPane.HttpWarning) return
        viewModelScope.launch { save { it.copy(allowCleartext = true) } }
    }

    // --- certificates -------------------------------------------------------------------------------------------

    fun forgetCertificate() {
        viewModelScope.launch { save(closePane = false) { it.copy(pinnedSpkiSha256 = null) } }
    }

    /** Reads the certificate the server presents now and shows it; nothing is trusted until [trustReviewedCertificate]. */
    fun reviewCertificate() {
        local.update { it.copy(pane = EditPane.CertificateReview(loading = true, certificate = null, error = null), error = null) }
        viewModelScope.launch {
            val failure = AppError.of(AppError.Kind.TLS_UNTRUSTED).userMessage
            val pane = try {
                val address = catalog.server(serverId)?.addresses?.firstOrNull { it.isSecure }
                val info = address?.let { prober.probe(it) }
                if (info == null) EditPane.CertificateReview(false, null, failure) else EditPane.CertificateReview(false, info.toSummary(), null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EditPane.CertificateReview(false, null, AppError.from(e).userMessage)
            }
            local.update { if (it.pane is EditPane.CertificateReview) it.copy(pane = pane) else it }
        }
    }

    fun trustReviewedCertificate() {
        val shown = (local.value.pane as? EditPane.CertificateReview)?.certificate ?: return
        viewModelScope.launch { save { it.copy(pinnedSpkiSha256 = shown.spkiSha256, pinInvalid = false) } }
    }

    suspend fun chooseClientCertificate(chooser: ClientCertChooser) {
        val alias = chooser.choose() ?: return
        save(closePane = false) { it.copy(clientCertAlias = alias) }
    }

    fun clearClientCertificate() {
        viewModelScope.launch { save(closePane = false) { it.copy(clientCertAlias = null) } }
    }

    // --- custom headers -----------------------------------------------------------------------------------------

    fun addHeader() = local.update { it.copy(pane = EditPane.Header(null, "", "", null), error = null) }

    fun editHeader(name: String) {
        viewModelScope.launch {
            val value = catalog.server(serverId)?.customHeaders?.get(name) ?: return@launch
            local.update { it.copy(pane = EditPane.Header(name, name, value, null), error = null) }
        }
    }

    fun onHeaderName(text: String) = updateHeader { it.copy(name = text, error = null) }
    fun onHeaderValue(text: String) = updateHeader { it.copy(value = text, error = null) }

    private fun updateHeader(change: (EditPane.Header) -> EditPane.Header) =
        local.update { l -> (l.pane as? EditPane.Header)?.let { l.copy(pane = change(it)) } ?: l }

    /** Validates with the data layer's own rule; the message names the header, never its value. */
    fun saveHeader() {
        val pane = local.value.pane as? EditPane.Header ?: return
        val name = pane.name.trim()
        try {
            validateCustomHeader(name, pane.value)
        } catch (e: IllegalArgumentException) {
            updateHeader { it.copy(error = e.message) }
            return
        }
        viewModelScope.launch {
            save { server ->
                val others = server.customHeaders.filterKeys { !it.equals(name, ignoreCase = true) && it != pane.originalName }
                server.copy(customHeaders = others + (name to pane.value))
            }
        }
    }

    fun removeHeader(name: String) {
        viewModelScope.launch { save(closePane = false) { it.copy(customHeaders = it.customHeaders - name) } }
    }

    // --- saving -------------------------------------------------------------------------------------------------

    /** Reads the saved server, applies [change] and saves it. A failure is shown as an [AppError] message. */
    private suspend fun save(closePane: Boolean = true, change: (Server) -> Server) {
        local.update { it.copy(busy = true, error = null) }
        try {
            val server = catalog.server(serverId) ?: return
            catalog.upsertServer(change(server))
            local.update { it.copy(busy = false, pane = if (closePane) null else it.pane) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            local.update { it.copy(busy = false, error = AppError.from(e).userMessage) }
        } finally {
            local.update { it.copy(busy = false) }
        }
    }
}
