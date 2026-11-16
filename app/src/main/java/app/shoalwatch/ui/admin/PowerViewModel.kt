package app.shoalwatch.ui.admin

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.viewModelScope
import app.shoalwatch.R
import app.shoalwatch.data.admin.Admin
import app.shoalwatch.data.admin.AdminRights
import app.shoalwatch.data.admin.ServerStatus
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerConnections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant

enum class PowerAction { RESTART, SHUT_DOWN }

data class PowerUiState(
    val frame: AdminFrame = AdminFrame(),
    val status: ServerStatus? = null,
    val confirm: PowerAction? = null,
    val canConfirm: Boolean = false,
    /** What was asked of the server and accepted; buttons stay off afterwards on this screen. */
    val requested: PowerAction? = null,
) {
    /** Restart and Shut down are on only when the screen can act, the server's name is known and nothing was asked yet. */
    val canAsk: Boolean get() = frame.canAct && status != null && requested == null

    override fun toString() = "PowerUiState(<redacted>)"
}

/** The word the admin types to confirm [action] ("restart", "shut down"), from the strings. */
val PowerAction.word: Int get() = if (this == PowerAction.RESTART) R.string.admin_word_restart else R.string.admin_word_shutdown

/**
 * Restart or shut down the server, each behind a typed confirmation that names the server. Sent once, never retried; no
 * automatic re-check afterwards (the app's normal address selector shows the server going offline). [words] gives the typed
 * word for each action (the route reads it from the strings; nothing here holds a Context).
 */
class PowerViewModel(
    catalog: ServerCatalog,
    rights: AdminRights,
    connections: ServerConnections,
    private val admin: Admin,
    clock: () -> Instant = Instant::now,
    private val words: (PowerAction) -> String,
) : AdminScreenModel(catalog, rights, connections, clock) {
    private val status = MutableStateFlow<ServerStatus?>(null)
    private val confirmState = MutableStateFlow<PowerAction?>(null)
    private val requestedState = MutableStateFlow<PowerAction?>(null)

    /** The typed word; never in UiState, cleared on dismiss, after confirming and when the gate closes. */
    val typed = TextFieldState()

    private val matches = combine(snapshotFlow { typed.text.toString() }, confirmState) { text, action ->
        action != null && typedMatches(text, words(action))
    }

    private val poll = poller { session ->
        val answer = admin.serverStatus(session)
        if (guard.gate.value is AdminGate.Open) status.value = answer
        null
    }

    val state: StateFlow<PowerUiState> = combine(frames, status, confirmState, matches, requestedState) { frame, status, confirm, canConfirm, requested ->
        if (frame.gate == AdminGate.Closed) PowerUiState(frame = frame) else PowerUiState(frame, status, confirm, canConfirm, requested)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PowerUiState())

    fun refresh() = poll.kick()

    fun ask(action: PowerAction) {
        if (!state.value.canAsk) return
        typed.clearText()
        confirmState.value = action
    }

    /** Sends the confirmed action once, only when the word was typed and the screen can act; the typed text is cleared either way. */
    fun confirm() {
        val action = confirmState.value ?: return
        val typedNow = typed.text.toString()
        if (!typedMatches(typedNow, words(action)) || !frames.value.canAct || requestedState.value != null) return
        confirmState.value = null
        typed.clearText()
        work.run("power", { s -> if (action == PowerAction.RESTART) admin.restart(s) else admin.shutDown(s) }) {
            requestedState.value = action
            notify(AdminNotice(if (action == PowerAction.RESTART) R.string.admin_restart_requested else R.string.admin_shutdown_requested))
        }
    }

    fun dismiss() {
        confirmState.value = null
        typed.clearText()
    }

    override fun dropLoaded() {
        status.value = null
        confirmState.value = null
        requestedState.value = null
        typed.clearText()
    }
}
