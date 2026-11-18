package app.shoalwatch.ui.servers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.Authenticator
import app.shoalwatch.data.servers.CertificateProber
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.data.servers.ServerNetDraft
import app.shoalwatch.net.fingerprintForDisplay
import app.shoalwatch.net.validateCustomHeader
import app.shoalwatch.security.Household
import app.shoalwatch.ui.AddressRole
import app.shoalwatch.ui.CertificateSummary
import app.shoalwatch.ui.ClientCertChooser
import app.shoalwatch.ui.ConnectionView
import app.shoalwatch.ui.onboarding.AddressChecker
import app.shoalwatch.ui.onboarding.Check
import app.shoalwatch.ui.toSummary
import app.shoalwatch.ui.lock.Door
import app.shoalwatch.ui.lock.HouseholdDoor
import app.shoalwatch.ui.toView
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

/** The destructive actions on the settings screen, each behind a confirmation. */
sealed interface Destructive {
    data object ForgetCertificate : Destructive
    data object RemoveClientCertificate : Destructive
    data class RemoveHeader(val name: String) : Destructive {
        override fun toString() = "RemoveHeader(<redacted>)"
    }
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

    /** Waiting for the user to confirm something that cannot be undone. */
    data class ConfirmDestructive(val action: Destructive) : EditPane

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
    /** Removing the address would leave the server with none. */
    val addressRequired: Boolean = false,
    /** Which address the open editor and its checks are about. */
    val addressRole: AddressRole? = null,
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
    private val auth: Authenticator,
    private val prober: CertificateProber,
    private val connections: ServerConnections,
    /** The household check for the whole screen (security review M8 F2); null only in tests. */
    household: Household?,
) : ViewModel() {
    internal val door = HouseholdDoor(household, viewModelScope)
    private data class Local(val pane: EditPane? = null, val busy: Boolean = false, val error: String? = null, val addressRequired: Boolean = false)

    /** What the address check was started with, so only what the check itself changed is merged into the server. */
    private var checkStart = ServerNetDraft()

    private val local = MutableStateFlow(Local())
    private val checker = AddressChecker(auth, prober, viewModelScope, ::addressVerified)

    private val serverWithConnection = combine(
        catalog.observeServer(serverId),
        connections.connection(serverId),
    ) { server, connection -> server to server?.let { connection.toView() } }

    val state: StateFlow<ServerEditUiState> =
        combine(serverWithConnection, local, checker.state, door.state) { (server, connection), local, check, door ->
            if (door != Door.Open) {
                ServerEditUiState() // nothing of the server (addresses, header values) until the household check passes
            } else if (server == null) {
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
                    addressRequired = local.addressRequired,
                    addressRole = (local.pane as? EditPane.Address)?.slot?.let { if (it == AddressSlot.Home) AddressRole.Home else AddressRole.Away },
                )
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ServerEditUiState())

    fun retry() = connections.refresh()
    fun dismissError() = local.update { it.copy(error = null) }

    fun dismissPane() {
        checker.cancel()
        local.update { it.copy(pane = null, addressRequired = false) }
    }

    // --- addresses ----------------------------------------------------------------------------------------------

    fun editAddress(slot: AddressSlot) {
        if (!door.isOpen) return
        viewModelScope.launch {
            val server = catalog.server(serverId) ?: return@launch
            val current = if (slot == AddressSlot.Home) server.homeAddress else server.awayAddress
            local.update { it.copy(pane = EditPane.Address(slot, current?.value.orEmpty()), error = null) }
        }
    }

    fun onAddressText(text: String) {
        local.update { l -> (l.pane as? EditPane.Address)?.let { l.copy(pane = it.copy(text = text), addressRequired = false) } ?: l }
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
                    local.update { it.copy(addressRequired = true) }
                } else {
                    save { it.withAddress(pane.slot, null).tidied() }
                }
            } else {
                // A new or edited address never inherits the server's consent to plain http: it is warned about again.
                // The certificate pin and the client-certificate alias ARE carried over on purpose: they belong to the
                // server, not to one address, and keeping them can only make the check stricter (fail-closed).
                checkStart = ServerNetDraft(false, server.pinnedSpkiSha256, server.clientCertAlias, server.customHeaders)
                checker.start(text, checkStart, server.id)
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
        // The saved server is read again here: only what this check changed (a new consent, pin or certificate) is merged,
        // so a certificate forgotten while the check ran stays forgotten.
        save { current ->
            val pin = if (draft.pinnedSpkiSha256 != checkStart.pinnedSpkiSha256) draft.pinnedSpkiSha256 else current.pinnedSpkiSha256
            val alias = if (draft.clientCertAlias != checkStart.clientCertAlias) draft.clientCertAlias else current.clientCertAlias
            current.withAddress(pane.slot, result.info.address).copy(
                allowCleartext = current.allowCleartext || draft.allowCleartext,
                pinnedSpkiSha256 = pin,
                clientCertAlias = alias,
                pinInvalid = current.pinInvalid && pin == null,
            ).tidied()
        }
    }

    /** Consent to plain http lapses by itself once no address uses http any more. */
    private fun Server.tidied() = copy(allowCleartext = allowCleartext && addresses.any { !it.isSecure })

    private fun Server.withAddress(slot: AddressSlot, address: app.shoalwatch.net.ServerAddress?) =
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
        viewModelScope.launch { save { it.copy(allowCleartext = true).tidied() } }
    }

    // --- certificates -------------------------------------------------------------------------------------------

    fun askForgetCertificate() = ask(Destructive.ForgetCertificate)
    fun askRemoveClientCertificate() = ask(Destructive.RemoveClientCertificate)
    fun askRemoveHeader(name: String) = ask(Destructive.RemoveHeader(name))

    private fun ask(action: Destructive) = local.update { it.copy(pane = EditPane.ConfirmDestructive(action), error = null) }

    /** Carries out what the user confirmed. */
    fun confirmDestructive() {
        val action = (local.value.pane as? EditPane.ConfirmDestructive)?.action ?: return
        viewModelScope.launch {
            save {
                when (action) {
                    Destructive.ForgetCertificate -> it.copy(pinnedSpkiSha256 = null)
                    Destructive.RemoveClientCertificate -> it.copy(clientCertAlias = null)
                    is Destructive.RemoveHeader -> it.copy(customHeaders = it.customHeaders - action.name)
                }
            }
        }
    }

    /** Reads the certificate the server presents now and shows it; nothing is trusted until [trustReviewedCertificate]. */
    fun reviewCertificate() {
        if (!door.isOpen) return
        local.update { it.copy(pane = EditPane.CertificateReview(loading = true, certificate = null, error = null), error = null) }
        viewModelScope.launch {
            val failure = AppError.of(AppError.Kind.TLS_UNTRUSTED).userMessage
            val pane = try {
                val address = catalog.server(serverId)?.addresses?.firstOrNull { it.isSecure }
                reviewedAddress = address
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

    /** The address [reviewCertificate] read the certificate from. */
    private var reviewedAddress: app.shoalwatch.net.ServerAddress? = null

    /**
     * Pins the certificate on screen, but only after the address answers, with that new pin, as this same server
     * (a changed certificate could belong to a different machine now holding the address).
     */
    fun trustReviewedCertificate() {
        val review = local.value.pane as? EditPane.CertificateReview ?: return
        val shown = review.certificate ?: return
        val address = reviewedAddress ?: return
        if (local.value.busy) return
        viewModelScope.launch {
            local.update { it.copy(busy = true) }
            val failure = try {
                val server = catalog.server(serverId) ?: return@launch
                val draft = ServerNetDraft(server.allowCleartext, shown.spkiSha256, server.clientCertAlias, server.customHeaders)
                if (auth.findServer(address.value, draft).id == server.id) null else AppError.of(AppError.Kind.SERVER_MISMATCH).userMessage
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppError.from(e).userMessage
            } finally {
                local.update { it.copy(busy = false) }
            }
            if (failure != null) {
                local.update { l -> (l.pane as? EditPane.CertificateReview)?.let { l.copy(pane = it.copy(error = failure)) } ?: l }
            } else {
                save { it.copy(pinnedSpkiSha256 = shown.spkiSha256, pinInvalid = false) }
            }
        }
    }

    suspend fun chooseClientCertificate(chooser: ClientCertChooser) {
        val alias = chooser.choose() ?: return
        save(closePane = false) { it.copy(clientCertAlias = alias) }
    }

    // --- custom headers -----------------------------------------------------------------------------------------

    fun addHeader() = local.update { it.copy(pane = EditPane.Header(null, "", "", null), error = null) }

    fun editHeader(name: String) {
        if (!door.isOpen) return
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
