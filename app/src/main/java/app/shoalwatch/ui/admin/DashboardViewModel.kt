package app.shoalwatch.ui.admin

import android.content.Context
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.viewModelScope
import app.shoalwatch.R
import app.shoalwatch.core.AppError
import app.shoalwatch.data.admin.Admin
import app.shoalwatch.data.admin.AdminRights
import app.shoalwatch.data.admin.DashboardOpener
import app.shoalwatch.data.admin.DashboardResult
import app.shoalwatch.data.admin.RemoteCommand
import app.shoalwatch.data.admin.ServerStatus
import app.shoalwatch.data.admin.WatchSession
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerConnections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant

/**
 * A Pause or Stop waiting for the admin's confirmation, naming who, where and what. [itemId] is what was playing when it was
 * asked: if the session is playing something else by the time it is confirmed, nothing is sent.
 */
sealed interface SessionConfirm {
    val sessionId: String
    val itemId: String?

    data class Pause(
        override val sessionId: String, val userName: String, val deviceName: String, val title: String, override val itemId: String? = null,
    ) : SessionConfirm {
        override fun toString() = "Pause(<redacted>)"
    }

    data class Stop(
        override val sessionId: String, val userName: String, val deviceName: String, val title: String, override val itemId: String? = null,
    ) : SessionConfirm {
        override fun toString() = "Stop(<redacted>)"
    }
}

/** Who and where the confirmation names, whichever command it is. */
internal val SessionConfirm.who: Triple<String, String, String>
    get() = when (this) {
        is SessionConfirm.Pause -> Triple(userName, deviceName, title)
        is SessionConfirm.Stop -> Triple(userName, deviceName, title)
    }

/** Who a message is being written to, and what they were playing then ([itemId], checked again before sending). */
data class MessageTarget(val sessionId: String, val userName: String, val deviceName: String, val itemId: String? = null) {
    override fun toString() = "MessageTarget(<redacted>)"
}

/** How often "Who is watching" (and the server card with it) reloads while the dashboard is on screen. */
const val SESSIONS_POLL_MS = 5_000L

data class DashboardUiState(
    val frame: AdminFrame = AdminFrame(),
    val status: ServerStatus? = null,
    val playing: List<WatchSession> = emptyList(),
    val idle: List<WatchSession> = emptyList(),
    val confirm: SessionConfirm? = null,
    val message: MessageTarget? = null,
) {
    override fun toString() = "DashboardUiState(<redacted>)"
}

/**
 * The Server admin dashboard: the server card, who is watching (reloaded every [SESSIONS_POLL_MS] while on screen), Message,
 * Pause and Stop for someone else's session after confirmation, and the links to every admin screen.
 */
class DashboardViewModel(
    private val catalog: ServerCatalog,
    rights: AdminRights,
    connections: ServerConnections,
    private val admin: Admin,
    private val opener: DashboardOpener,
    clock: () -> Instant = Instant::now,
) : AdminScreenModel(catalog, rights, connections, clock) {
    private val status = MutableStateFlow<ServerStatus?>(null)
    private val sessions = MutableStateFlow<List<WatchSession>>(emptyList())
    private val confirmState = MutableStateFlow<SessionConfirm?>(null)
    private val messageState = MutableStateFlow<MessageTarget?>(null)
    /** The signed-in account, for the message header ("Message from ben"); used only while it is the gate's session. */
    private val me = catalog.activeAccount.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The message being written; cleared when the dialog closes or the gate closes. Never in UiState, never saved. */
    val messageText = TextFieldState()

    private val poll = poller { session ->
        val server = admin.serverStatus(session)
        val list = admin.sessions(session)
        // An answer that lands after the gate closed is dropped, not kept in memory.
        if (guard.gate.value is AdminGate.Open) {
            status.value = server
            sessions.value = list
        }
        SESSIONS_POLL_MS
    }

    val state: StateFlow<DashboardUiState> = combine(frames, status, sessions, confirmState, messageState) { frame, status, list, confirm, message ->
        if (frame.gate == AdminGate.Closed) {
            DashboardUiState(frame = frame)
        } else {
            val (playing, idle) = list.partition { it.nowPlaying != null }
            DashboardUiState(frame, status, playing, idle, confirm, message)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DashboardUiState())

    fun refresh() = poll.kick()

    /** [context] is used for this call only and never kept. */
    fun openDashboard(context: Context) {
        work.run(KEY_DASHBOARD, { session ->
            when (opener.open(session, context)) {
                DashboardResult.OFFLINE -> throw AppError.of(AppError.Kind.OFFLINE)
                DashboardResult.NO_BROWSER -> notify(AdminNotice(R.string.admin_no_browser))
                DashboardResult.OPENED -> Unit
            }
        })
    }

    fun askPause(sessionId: String) {
        val s = controllable(sessionId)?.takeIf { it.nowPlaying?.paused == false } ?: return
        confirmState.value = SessionConfirm.Pause(s.id, s.userName, s.deviceName, s.nowPlaying?.title.orEmpty(), s.nowPlaying?.itemId)
    }

    fun askStop(sessionId: String) {
        val s = controllable(sessionId) ?: return
        confirmState.value = SessionConfirm.Stop(s.id, s.userName, s.deviceName, s.nowPlaying?.title.orEmpty(), s.nowPlaying?.itemId)
    }

    /**
     * Sends the confirmed Pause or Stop once, if that session is still in the latest list and still playing what the dialog
     * named (else GONE or CHANGED, nothing sent); the dialog closes either way.
     */
    fun confirmCommand() {
        val c = confirmState.value ?: return
        confirmState.value = null
        if (frames.value.gate !is AdminGate.Open) return
        val now = controllable(c.sessionId)
        if (now == null) {
            gone()
            return
        }
        if (now.nowPlaying?.itemId != c.itemId || now.nowPlaying?.title.orEmpty() != c.who.third) {
            changed()
            return
        }
        val (command, done) = when (c) {
            is SessionConfirm.Pause -> RemoteCommand.PAUSE to R.string.admin_paused_done
            is SessionConfirm.Stop -> RemoteCommand.STOP to R.string.admin_stopped_done
        }
        work.run("command:${c.sessionId}", { session -> admin.command(session, c.sessionId, command) }) {
            notify(AdminNotice(done, listOf(c.who.first), R.string.admin_unnamed_user))
            poll.kick()
        }
    }

    fun dismissConfirm() {
        confirmState.value = null
    }

    fun askMessage(sessionId: String) {
        val s = messageable(sessionId) ?: return
        messageText.clearText()
        messageState.value = MessageTarget(s.id, s.userName, s.deviceName, s.nowPlaying?.itemId)
    }

    /**
     * Sends the trimmed message once as "Message from <the admin's user name>" (refused while blank); the dialog closes and the
     * text is cleared. [context] is used only to word the header and is never kept.
     */
    fun sendMessage(context: Context) {
        val target = messageState.value ?: return
        val text = messageText.text.toString().trim()
        if (text.isEmpty()) return
        val session = (frames.value.gate as? AdminGate.Open)?.session ?: return
        val account = me.value?.takeIf { it.serverId == session.serverId && it.userId == session.userId } ?: return
        val header = context.getString(R.string.admin_message_header, account.userName)
        dismissMessage()
        val now = messageable(target.sessionId)
        if (now == null) {
            gone()
            return
        }
        if (now.nowPlaying?.itemId != target.itemId) {
            changed()
            return
        }
        work.run("message:${target.sessionId}", { s -> admin.sendMessage(s, target.sessionId, header, text) }) {
            notify(AdminNotice(R.string.admin_message_sent, listOf(target.userName), R.string.admin_unnamed_user))
        }
    }

    fun dismissMessage() {
        messageState.value = null
        messageText.clearText()
    }

    override fun dropLoaded() {
        status.value = null
        sessions.value = emptyList()
        confirmState.value = null
        messageState.value = null
        messageText.clearText()
    }

    /** A session in the latest list that is playing, can be controlled and is not this phone. */
    private fun controllable(id: String) = sessions.value.firstOrNull { it.id == id && it.nowPlaying != null && it.canControl && !it.isThisDevice }

    private fun messageable(id: String) = sessions.value.firstOrNull { it.id == id && it.canMessage && !it.isThisDevice }

    private fun gone() {
        work.fail(AdminProblem.GONE)
        poll.kick()
    }

    /** The session is still there but now playing something the dialog did not name. */
    private fun changed() {
        work.fail(AdminProblem.CHANGED)
        poll.kick()
    }

    private companion object {
        const val KEY_DASHBOARD = "dashboard"
    }
}
