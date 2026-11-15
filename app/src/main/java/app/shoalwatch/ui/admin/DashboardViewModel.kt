package app.shoalwatch.ui.admin

import android.content.Context
import androidx.lifecycle.viewModelScope
import app.shoalwatch.R
import app.shoalwatch.core.AppError
import app.shoalwatch.data.admin.Admin
import app.shoalwatch.data.admin.AdminRights
import app.shoalwatch.data.admin.DashboardOpener
import app.shoalwatch.data.admin.DashboardResult
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

/** A Pause or Stop waiting for the admin's confirmation, naming who and where. */
sealed interface SessionConfirm {
    val sessionId: String

    data class Pause(override val sessionId: String, val userName: String, val deviceName: String, val title: String) : SessionConfirm {
        override fun toString() = "Pause(<redacted>)"
    }

    data class Stop(override val sessionId: String, val userName: String, val deviceName: String, val title: String) : SessionConfirm {
        override fun toString() = "Stop(<redacted>)"
    }
}

/** Who a message is being written to. */
data class MessageTarget(val sessionId: String, val userName: String, val deviceName: String) {
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

/** The Server admin dashboard: the server card and the links to every admin screen (Task 7 adds who is watching). */
class DashboardViewModel(
    catalog: ServerCatalog,
    rights: AdminRights,
    connections: ServerConnections,
    private val admin: Admin,
    private val opener: DashboardOpener,
    clock: () -> Instant = Instant::now,
) : AdminScreenModel(catalog, rights, connections, clock) {
    private val status = MutableStateFlow<ServerStatus?>(null)

    private val poll = poller { session ->
        status.value = admin.serverStatus(session)
        SESSIONS_POLL_MS
    }

    val state: StateFlow<DashboardUiState> = combine(frames, status) { frame, status ->
        if (frame.gate == AdminGate.Closed) DashboardUiState(frame = frame) else DashboardUiState(frame = frame, status = status)
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

    override fun dropLoaded() {
        status.value = null
    }

    private companion object {
        const val KEY_DASHBOARD = "dashboard"
    }
}
