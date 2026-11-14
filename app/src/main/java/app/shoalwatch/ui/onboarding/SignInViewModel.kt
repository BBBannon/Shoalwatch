package app.shoalwatch.ui.onboarding

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.AuthRepository
import app.shoalwatch.data.servers.Authenticator
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.QuickConnectProgress
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.data.servers.SignInRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class SignInMode { QuickConnect, Password }

/** Progress of the Quick Connect panel. */
sealed interface QuickConnectUi {
    data object Starting : QuickConnectUi

    /** [code] is shown large; [secondsLeft] counts down to the timeout. */
    data class Code(val code: String, val secondsLeft: Int) : QuickConnectUi {
        override fun toString() = "Code(<redacted>)"
    }
    data object Authenticating : QuickConnectUi
    data object TimedOut : QuickConnectUi
    data class Failed(val message: String) : QuickConnectUi
}

/**
 * Everything the Sign in screen shows. The password is deliberately not here: it lives in
 * [SignInViewModel.password] and is cleared the moment it is submitted.
 */
data class SignInUiState(
    /** Null until the server has been loaded. */
    val serverName: String? = null,
    val mode: SignInMode = SignInMode.QuickConnect,
    val quickConnect: QuickConnectUi = QuickConnectUi.Starting,
    /** Quick Connect is switched off on this server, so only the password is offered. */
    val quickConnectUnavailable: Boolean = false,
    val username: String = "",
    val submitting: Boolean = false,
    val error: String? = null,
    val signedIn: Boolean = false,
    /** The server no longer exists (removed while signing in). */
    val serverMissing: Boolean = false,
    /**
     * The sign-in (password or Quick Connect) would go over plain http: the server has no https address, or it
     * could not be reached. Shown as a persistent notice; it never blocks.
     */
    val unencrypted: Boolean = false,
) {
    /** The username and Quick Connect code never appear. */
    override fun toString() = "SignInUiState(mode=$mode, submitting=$submitting, signedIn=$signedIn, unencrypted=$unencrypted)"
}

/**
 * Quick Connect first, password as the alternative. Polling lives in one cancellable job: switching to the
 * password, starting again, or leaving the screen (the ViewModel is cleared) cancels the collection, which
 * stops the polling. This screen never chooses an address: before every attempt it asks `AuthRepository` for a
 * [SignInRoute] (a verified address, https preferred), shows whether that route is encrypted, and hands the
 * route back; `AuthRepository` re-reads the server and re-checks its identity before sending anything.
 */
class SignInViewModel(
    private val serverId: String,
    private val auth: Authenticator,
    private val catalog: ServerCatalog,
    private val connections: ServerConnections,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SignInUiState())
    val state: StateFlow<SignInUiState> = mutableState

    /** Held outside [state] so it never reaches a state snapshot or log; cleared on submit. */
    val password = TextFieldState()

    private var loaded = false
    private var quickConnectJob: Job? = null
    private var countdownJob: Job? = null

    init {
        viewModelScope.launch {
            val found = catalog.server(serverId)
            if (found == null) {
                mutableState.update { it.copy(serverMissing = true) }
            } else {
                loaded = true
                mutableState.update { it.copy(serverName = found.name) }
                startQuickConnect()
            }
        }
    }

    /** A fresh route for the next attempt; the notice always reflects the route about to be used. */
    private suspend fun route(): SignInRoute? =
        auth.signInRoute(serverId).also { r -> if (r != null) mutableState.update { it.copy(unencrypted = !r.encrypted) } }

    fun onUsernameChange(text: String) = mutableState.update { it.copy(username = text, error = null) }

    fun usePassword() {
        stopQuickConnect()
        mutableState.update { it.copy(mode = SignInMode.Password, error = null) }
        // So the notice is right while the password is typed; the route is asked for again on submit.
        viewModelScope.launch {
            try {
                route()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    fun useQuickConnect() = startQuickConnect()
    fun retryQuickConnect() = startQuickConnect()

    private fun stopQuickConnect() {
        quickConnectJob?.cancel()
        countdownJob?.cancel()
    }

    private fun startQuickConnect() {
        if (!loaded) return
        stopQuickConnect()
        mutableState.update { it.copy(mode = SignInMode.QuickConnect, quickConnect = QuickConnectUi.Starting, error = null) }
        quickConnectJob = viewModelScope.launch {
            try {
                val sawNotice = mutableState.value.unencrypted
                val route = route()
                if (route == null) {
                    mutableState.update { it.copy(quickConnect = QuickConnectUi.Failed(unreachableMessage())) }
                    return@launch
                }
                if (!route.encrypted && !sawNotice) {
                    // The user was looking at a secure screen: nothing is sent until they have seen the notice (now up) and ask again.
                    mutableState.update {
                        it.copy(quickConnect = QuickConnectUi.Failed(AppError.of(AppError.Kind.SECURE_CONNECTION_LOST_QUICK_CONNECT).userMessage))
                    }
                    return@launch
                }
                auth.quickConnect(route).collect(::onQuickConnect)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(quickConnect = QuickConnectUi.Failed(AppError.from(e).userMessage)) }
            }
        }
    }

    private fun onQuickConnect(progress: QuickConnectProgress) {
        if (progress !is QuickConnectProgress.AwaitingApproval) countdownJob?.cancel()
        when (progress) {
            QuickConnectProgress.Starting -> mutableState.update { it.copy(quickConnect = QuickConnectUi.Starting) }
            is QuickConnectProgress.AwaitingApproval -> {
                mutableState.update { it.copy(quickConnect = QuickConnectUi.Code(progress.code, TIMEOUT_SECONDS)) }
                startCountdown()
            }
            QuickConnectProgress.Authenticating -> mutableState.update { it.copy(quickConnect = QuickConnectUi.Authenticating) }
            is QuickConnectProgress.Success -> mutableState.update { it.copy(signedIn = true) }
            QuickConnectProgress.TimedOut -> mutableState.update { it.copy(quickConnect = QuickConnectUi.TimedOut) }
            is QuickConnectProgress.Failed -> mutableState.update {
                if (progress.error.kind == AppError.Kind.QUICK_CONNECT_DISABLED) {
                    it.copy(mode = SignInMode.Password, quickConnectUnavailable = true)
                } else {
                    it.copy(quickConnect = QuickConnectUi.Failed(progress.error.userMessage))
                }
            }
        }
    }

    private fun startCountdown() {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            var left = TIMEOUT_SECONDS
            while (left > 0) {
                delay(1_000)
                left--
                mutableState.update { s -> (s.quickConnect as? QuickConnectUi.Code)?.let { s.copy(quickConnect = it.copy(secondsLeft = left)) } ?: s }
            }
        }
    }

    /**
     * Signs in with the typed username and password. The route is fetched first: if it is plain http while the screen
     * showed no notice, nothing is sent and the password stays in the field until the next explicit press. Otherwise
     * the password is copied out of the field, which is cleared at once, and the copy is wiped by `AuthRepository`;
     * it is never in [state].
     */
    fun signInWithPassword() {
        if (!loaded) return
        val state = mutableState.value
        val username = state.username.trim()
        if (state.submitting || username.isEmpty() || password.text.isEmpty()) return
        val sawNotice = state.unencrypted
        mutableState.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            var secret: CharArray? = null
            var keepPassword = false
            try {
                val route = route() ?: throw AppError.of(offlineKind())
                if (!route.encrypted && !sawNotice) {
                    // The screen said "encrypted" when the user pressed the button. The notice is up now; the password stays
                    // in the field and goes only when they press Sign in again.
                    keepPassword = true
                    throw AppError.of(AppError.Kind.SECURE_CONNECTION_LOST)
                }
                secret = CharArray(password.text.length) { password.text[it] }
                password.clearText()
                auth.signInWithPassword(route, username, secret)
                mutableState.update { it.copy(submitting = false, signedIn = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(submitting = false, error = AppError.from(e).userMessage) }
            } finally {
                secret?.fill(' ')
                if (!keepPassword) password.clearText()
            }
        }
    }

    private fun unreachableMessage() = AppError.of(offlineKind()).userMessage

    /** A security problem on the connection is reported as that; anything else is plain unreachability. */
    private fun offlineKind(): AppError.Kind =
        (connections.connection(serverId).value as? Connection.Offline)?.reason ?: AppError.Kind.NETWORK

    private companion object {
        val TIMEOUT_SECONDS = (AuthRepository.TIMEOUT_MS / 1_000).toInt()
    }
}
