package app.offlinefin.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.offlinefin.core.AppError
import app.offlinefin.data.servers.Authenticator
import app.offlinefin.data.servers.CertificateProber
import app.offlinefin.data.servers.Server
import app.offlinefin.data.servers.ServerCatalog
import app.offlinefin.data.servers.ServerInfo
import app.offlinefin.data.servers.ServerNetDraft
import app.offlinefin.data.servers.looksLikeHome
import app.offlinefin.net.ServerAddress
import app.offlinefin.ui.ClientCertChooser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AddServerStage { Address, Found, SecondAddress }

/** The server that answered, held until the user finishes the optional second-address step and it is saved. */
data class FoundServer(
    val id: String,
    val name: String,
    val version: String,
    /** Anything but [ServerInfo.Compatibility.SUPPORTED] is shown as a non-blocking banner. */
    val compatibility: ServerInfo.Compatibility,
    /** The address the user typed was sorted as a home (local network) address; otherwise away. */
    val firstIsHome: Boolean,
)

/** Everything the Add server screen shows. Addresses are user text, so [toString] hides them. */
data class AddServerUiState(
    val stage: AddServerStage = AddServerStage.Address,
    val address: String = "",
    val secondAddress: String = "",
    val check: Check = Check.Idle,
    val found: FoundServer? = null,
    val saving: Boolean = false,
    val error: String? = null,
    /** Set once the server is saved (or was already saved); the screen moves on to sign-in. */
    val savedServerId: String? = null,
) {
    override fun toString() = "AddServerUiState(stage=$stage, check=${check::class.simpleName})"
}

/**
 * Add server, in steps. Every warning (plain http, untrusted certificate, client certificate) is an explicit
 * [Check] state owned by [AddressChecker]; nothing reaches the store until the user has finished the optional
 * second-address step, so cancelling anywhere saves nothing.
 */
class AddServerViewModel(
    auth: Authenticator,
    prober: CertificateProber,
    private val catalog: ServerCatalog,
) : ViewModel() {
    private val checker = AddressChecker(auth, prober, viewModelScope, ::verified)
    private val form = MutableStateFlow(AddServerUiState())

    val state: StateFlow<AddServerUiState> =
        combine(form, checker.state) { f, c -> f.copy(check = c) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, form.value)

    /** The first address that answered and the security choices made for it, until saved. */
    private var firstAddress: ServerAddress? = null
    private var foundDraft = ServerNetDraft()

    fun onAddressChange(text: String) {
        form.update { it.copy(address = text, error = null) }
        if (checker.state.value is Check.Failed) checker.cancel()
    }

    fun onSecondAddressChange(text: String) {
        form.update { it.copy(secondAddress = text, error = null) }
        if (checker.state.value is Check.Failed) checker.cancel()
    }

    fun continueClicked() {
        val text = form.value.address
        if (text.isBlank()) return
        checker.start(text, ServerNetDraft())
    }

    fun cancelCheck() = checker.cancel()
    fun offerHttp() = checker.offerHttp()
    fun allowHttp() = checker.allowHttp()
    fun trustCertificate() = checker.trustCertificate()

    suspend fun chooseClientCertificate(chooser: ClientCertChooser) {
        chooser.choose()?.let(checker::useClientCertificate)
    }

    /** The user accepted the server (and any version warning): on to the optional second address. */
    fun confirmFound() {
        if (form.value.stage == AddServerStage.Found) form.update { it.copy(stage = AddServerStage.SecondAddress) }
    }

    fun addSecondAddress() {
        val state = form.value
        val found = state.found ?: return
        if (state.secondAddress.isBlank() || state.saving) return
        checker.start(state.secondAddress, foundDraft, expectedServerId = found.id)
    }

    fun skipSecondAddress() {
        if (form.value.saving) return
        viewModelScope.launch { save(second = null, draft = foundDraft) }
    }

    fun backToAddress() {
        checker.cancel()
        firstAddress = null
        form.update { it.copy(stage = AddServerStage.Address, found = null, secondAddress = "", error = null) }
    }

    /** Called by the checker once an address has answered as a Jellyfin server. */
    private suspend fun verified(result: Check.Verified) {
        when (form.value.stage) {
            AddServerStage.Address -> {
                val info = result.info
                firstAddress = info.address
                foundDraft = result.draft
                val found = FoundServer(info.id, info.name, info.version, info.compatibility, info.address.looksLikeHome())
                form.update { it.copy(stage = AddServerStage.Found, found = found, error = null) }
            }
            AddServerStage.SecondAddress -> save(second = result.info.address, draft = result.draft)
            AddServerStage.Found -> Unit
        }
    }

    /** Saves the server with the first address and, if given, the second in the other slot. An already saved server is left as it is. */
    private suspend fun save(second: ServerAddress?, draft: ServerNetDraft) {
        val found = form.value.found ?: return
        val first = firstAddress ?: return
        form.update { it.copy(saving = true, error = null) }
        try {
            if (catalog.server(found.id) == null) {
                val (home, away) = if (found.firstIsHome) first to second else second to first
                catalog.upsertServer(
                    Server(
                        id = found.id, name = found.name, version = found.version,
                        homeAddress = home, awayAddress = away,
                        allowCleartext = draft.allowCleartext, pinnedSpkiSha256 = draft.pinnedSpkiSha256,
                        clientCertAlias = draft.clientCertAlias, customHeaders = emptyMap(),
                    ),
                )
            }
            form.update { it.copy(saving = false, savedServerId = found.id) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            form.update { it.copy(saving = false, error = AppError.from(e).userMessage) }
        }
    }
}
