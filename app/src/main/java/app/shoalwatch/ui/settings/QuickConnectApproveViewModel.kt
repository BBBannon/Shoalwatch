package app.shoalwatch.ui.settings

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.ApproveOutcome
import app.shoalwatch.data.servers.QuickConnectApprover
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.Session
import app.shoalwatch.security.PinCheck
import app.shoalwatch.ui.lock.toPinArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class QuickConnectUiState(
    val session: Session? = null,
    val userName: String = "",
    val running: Boolean = false,
    val outcome: ApproveOutcome? = null,
    /** The profile has a PIN: it is asked for before anything is sent. */
    val pinAsk: Boolean = false,
    /** The last answer to a typed PIN (wrong, wait...), shown in the PIN dialog. */
    val pinAnswer: PinCheck? = null,
    /** The PIN could not be checked (shown as such; nothing was sent). */
    val pinFailed: Boolean = false,
) {
    override fun toString() = "QuickConnectUiState(<redacted>)"
}

/**
 * "Approve a sign-in on another device" (Milestone 10). The code lives only in [code] (never in the state, saved state or a log),
 * is cleared after an approval and whenever the active profile changes, and an approval runs for the profile that was active when
 * Approve was pressed; its answer is dropped if the profile changed meanwhile. One approval at a time. A PIN-protected profile must
 * type its PIN first ([isProtected], then the existing profile check [checkPin]: wrong tries and waits counted as everywhere), since
 * approving signs the other device in as this profile. The PIN lives only in [pin] and one array, zeroed after the check.
 */
class QuickConnectApproveViewModel(
    catalog: ServerCatalog,
    private val send: suspend (Session, String) -> ApproveOutcome,
    private val isProtected: suspend (Session) -> Boolean,
    private val checkPin: suspend (Session, CharArray) -> PinCheck,
) : ViewModel() {
    val code = TextFieldState()
    val pin = TextFieldState()
    private val _state = MutableStateFlow(QuickConnectUiState())
    val state: StateFlow<QuickConnectUiState> = _state.asStateFlow()
    private var running: Job? = null

    init {
        viewModelScope.launch {
            catalog.activeAccount.collect { a: AccountSummary? ->
                val s = a?.let { Session(it.serverId, it.userId) }
                if (s != _state.value.session) {
                    running?.cancel()
                    code.clearText()
                    pin.clearText()
                    _state.value = QuickConnectUiState(session = s, userName = a?.userName.orEmpty())
                }
            }
        }
    }

    /** Whether the typed code has the right shape (Approve is off until it does). */
    val codeReady: Boolean get() = QuickConnectApprover.normalise(code.text.toString()) != null

    fun approve() {
        val now = _state.value
        val s = now.session ?: return
        if (now.running || now.pinAsk) return
        _state.update { it.copy(running = true, outcome = null) }
        running = viewModelScope.launch {
            // Could not tell: treat as protected (asking for a PIN is the safe side).
            val locked = try {
                isProtected(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                true
            }
            if (_state.value.session != s) return@launch
            if (locked) {
                pin.clearText()
                _state.update { it.copy(running = false, pinAsk = true, pinAnswer = null, pinFailed = false) }
            } else {
                sendCode(s)
            }
        }
    }

    /** The PIN dialog's Approve: checks the typed PIN for the asking profile; only a right one sends the code. */
    fun submitPin() {
        val now = _state.value
        val s = now.session ?: return
        if (!now.pinAsk || now.running) return
        val typed = pin.text.toPinArray()
        pin.clearText()
        _state.update { it.copy(running = true) }
        running = viewModelScope.launch {
            val answer = try {
                checkPin(s, typed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } finally {
                typed.fill('\u0000')
            }
            if (_state.value.session != s) return@launch
            if (answer == PinCheck.Correct || answer == PinCheck.NotProtected) {
                _state.update { it.copy(pinAsk = false, pinAnswer = null, pinFailed = false) }
                sendCode(s)
            } else {
                _state.update { it.copy(running = false, pinAnswer = answer, pinFailed = answer == null) }
            }
        }
    }

    fun cancelPin() {
        if (_state.value.running) return
        pin.clearText()
        _state.update { it.copy(pinAsk = false, pinAnswer = null, pinFailed = false) }
    }

    private suspend fun sendCode(s: Session) {
        val outcome = try {
            send(s, code.text.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApproveOutcome.FAILED
        }
        if (_state.value.session != s) return
        if (outcome == ApproveOutcome.APPROVED) code.clearText()
        _state.update { it.copy(running = false, outcome = outcome) }
    }

    fun dismissOutcome() = _state.update { it.copy(outcome = null) }
}
