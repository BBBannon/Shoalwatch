package app.shoalwatch.ui.onboarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.Authenticator
import app.shoalwatch.data.servers.CertificateProber
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerInfo
import app.shoalwatch.data.servers.ServerNetDraft
import app.shoalwatch.data.servers.looksLikeHome
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.ui.AddressRole
import app.shoalwatch.ui.ClientCertChooser
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
    /** The server is already saved: its settings are left alone and the user goes straight to sign-in. */
    val alreadySaved: Boolean = false,
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
    /** Which address the current check or warning is about. */
    val addressRole: AddressRole? = null,
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
        combine(form, checker.state) { f, c -> f.copy(check = c, addressRole = roleOf(f)) }
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
        val state = form.value
        if (state.stage != AddServerStage.Found) return
        if (state.found?.alreadySaved == true) {
            // Its settings are left exactly as they are: straight to sign-in, with no step that could discard a second address.
            viewModelScope.launch { save(second = null, draft = foundDraft) }
        } else {
            form.update { it.copy(stage = AddServerStage.SecondAddress) }
        }
    }

    fun addSecondAddress() {
        val state = form.value
        val found = state.found ?: return
        if (state.secondAddress.isBlank() || state.saving) return
        // A new address never inherits consent given for another one: plain http is warned about again. The pin and
        // the client-certificate alias ARE carried over on purpose: they belong to the server, not to one address,
        // and keeping them can only make the check stricter (fail-closed).
        checker.start(state.secondAddress, foundDraft.copy(allowCleartext = false), expectedServerId = found.id)
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
                val saved = catalog.server(info.id) != null
                val found = FoundServer(info.id, info.name, info.version, info.compatibility, info.address.looksLikeHome(), saved)
                form.update { it.copy(stage = AddServerStage.Found, found = found, error = null) }
            }
            AddServerStage.SecondAddress -> save(second = result.info.address, draft = result.draft)
            AddServerStage.Found -> Unit
        }
    }

    private fun roleOf(f: AddServerUiState): AddressRole? = when (f.stage) {
        AddServerStage.Address ->
            runCatching { ServerAddress.parse(f.address.trim()).looksLikeHome() }.getOrNull()
                ?.let { if (it) AddressRole.Home else AddressRole.Away }
        AddServerStage.SecondAddress -> f.found?.let { if (it.firstIsHome) AddressRole.Away else AddressRole.Home }
        AddServerStage.Found -> null
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
                        allowCleartext = (foundDraft.allowCleartext || draft.allowCleartext) && listOfNotNull(first, second).any { !it.isSecure }, pinnedSpkiSha256 = draft.pinnedSpkiSha256,
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
