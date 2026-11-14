package app.offlinefin.ui.onboarding

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.offlinefin.core.AppError
import app.offlinefin.data.servers.AuthRepository
import app.offlinefin.data.servers.Authenticator
import app.offlinefin.data.servers.Connection
import app.offlinefin.data.servers.QuickConnectState
import app.offlinefin.data.servers.Server
import app.offlinefin.data.servers.ServerCatalog
import app.offlinefin.data.servers.ServerConnections
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
    data class Code(val code: String, val secondsLeft: Int) : QuickConnectUi
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
)

/**
 * Quick Connect first, password as the alternative. Polling lives in one cancellable job: switching to the
 * password, starting again, or leaving the screen (the ViewModel is cleared) cancels the collection, which
 * stops the polling. Every address a credential is sent to comes from `awaitOnline`, and `AuthRepository`
 * re-checks it is the right server before sending anything.
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

    private var server: Server? = null
    private var quickConnectJob: Job? = null
    private var countdownJob: Job? = null

    init {
        viewModelScope.launch {
            val found = catalog.server(serverId)
            if (found == null) {
                mutableState.update { it.copy(serverMissing = true) }
            } else {
                server = found
                mutableState.update { it.copy(serverName = found.name) }
                startQuickConnect()
            }
        }
    }

    fun onUsernameChange(text: String) = mutableState.update { it.copy(username = text, error = null) }

    fun usePassword() {
        stopQuickConnect()
        mutableState.update { it.copy(mode = SignInMode.Password, error = null) }
    }

    fun useQuickConnect() = startQuickConnect()
    fun retryQuickConnect() = startQuickConnect()

    private fun stopQuickConnect() {
        quickConnectJob?.cancel()
        countdownJob?.cancel()
    }

    private fun startQuickConnect() {
        val target = server ?: return
        stopQuickConnect()
        mutableState.update { it.copy(mode = SignInMode.QuickConnect, quickConnect = QuickConnectUi.Starting, error = null) }
        quickConnectJob = viewModelScope.launch {
            try {
                val via = connections.awaitOnline(target)
                if (via == null) {
                    mutableState.update { it.copy(quickConnect = QuickConnectUi.Failed(unreachableMessage(target))) }
                    return@launch
                }
                auth.quickConnect(target, via.address).collect(::onQuickConnect)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(quickConnect = QuickConnectUi.Failed(AppError.from(e).userMessage)) }
            }
        }
    }

    private fun onQuickConnect(progress: QuickConnectState) {
        if (progress !is QuickConnectState.AwaitingApproval) countdownJob?.cancel()
        when (progress) {
            QuickConnectState.Starting -> mutableState.update { it.copy(quickConnect = QuickConnectUi.Starting) }
            is QuickConnectState.AwaitingApproval -> {
                mutableState.update { it.copy(quickConnect = QuickConnectUi.Code(progress.code, TIMEOUT_SECONDS)) }
                startCountdown()
            }
            QuickConnectState.Authenticating -> mutableState.update { it.copy(quickConnect = QuickConnectUi.Authenticating) }
            is QuickConnectState.Success -> mutableState.update { it.copy(signedIn = true) }
            QuickConnectState.TimedOut -> mutableState.update { it.copy(quickConnect = QuickConnectUi.TimedOut) }
            is QuickConnectState.Failed -> mutableState.update {
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
     * Signs in with the typed username and password. The password is copied out of the field, which is cleared
     * at once, and the copy is wiped by `AuthRepository`; it is never in [state].
     */
    fun signInWithPassword() {
        val target = server ?: return
        val state = mutableState.value
        val username = state.username.trim()
        if (state.submitting || username.isEmpty() || password.text.isEmpty()) return
        val secret = CharArray(password.text.length) { password.text[it] }
        password.clearText()
        mutableState.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            try {
                val via = connections.awaitOnline(target) ?: throw AppError.of(offlineKind(target))
                auth.signInWithPassword(target, via.address, username, secret)
                mutableState.update { it.copy(submitting = false, signedIn = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutableState.update { it.copy(submitting = false, error = AppError.from(e).userMessage) }
            } finally {
                secret.fill('\u0000')
            }
        }
    }

    private fun unreachableMessage(target: Server) = AppError.of(offlineKind(target)).userMessage

    /** A security problem on the connection is reported as that; anything else is plain unreachability. */
    private fun offlineKind(target: Server): AppError.Kind =
        (connections.connection(target).value as? Connection.Offline)?.reason ?: AppError.Kind.NETWORK

    private companion object {
        val TIMEOUT_SECONDS = (AuthRepository.TIMEOUT_MS / 1_000).toInt()
    }
}
