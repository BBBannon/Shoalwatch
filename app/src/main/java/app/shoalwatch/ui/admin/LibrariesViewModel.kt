package app.shoalwatch.ui.admin

import androidx.lifecycle.viewModelScope
import app.shoalwatch.R
import app.shoalwatch.core.AppError
import app.shoalwatch.data.admin.Admin
import app.shoalwatch.data.admin.AdminRights
import app.shoalwatch.data.admin.FULL_SCAN_TASK_KEY
import app.shoalwatch.data.admin.LibraryFolder
import app.shoalwatch.data.admin.ServerTask
import app.shoalwatch.data.admin.TaskState
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant

/** While anything is scanning, the libraries screen reloads this often (else it waits for Refresh or an action). */
const val SCAN_POLL_MS = 3_000L

data class LibrariesUiState(
    val frame: AdminFrame = AdminFrame(),
    val libraries: List<LibraryFolder> = emptyList(),
    /** The "Scan Media Library" task: its state and progress are the full scan's. Null when the server has no such task. */
    val fullScan: ServerTask? = null,
) {
    val anyScanning: Boolean get() = fullScan?.state == TaskState.RUNNING || libraries.any { it.scanning }

    /** "Scan all libraries" is on unless the full scan is already running (and only when the screen can act). */
    val canScanAll: Boolean get() = frame.canAct && fullScan?.state != TaskState.RUNNING

    /** A library's Scan is on unless that library is already scanning. */
    fun canScan(library: LibraryFolder): Boolean = frame.canAct && !library.scanning

    override fun toString() = "LibrariesUiState(<redacted>)"
}

/**
 * Libraries: each library with its scan state, and scans (all, or one) started without a confirmation (a scan changes nothing
 * that cannot simply be repeated). Reloads every [SCAN_POLL_MS] while anything scans, otherwise only on Refresh or an action.
 */
class LibrariesViewModel(
    catalog: ServerCatalog,
    rights: AdminRights,
    connections: ServerConnections,
    private val admin: Admin,
    clock: () -> Instant = Instant::now,
) : AdminScreenModel(catalog, rights, connections, clock) {
    private val libraries = MutableStateFlow<List<LibraryFolder>>(emptyList())
    private val fullScan = MutableStateFlow<ServerTask?>(null)

    private val poll = poller { session ->
        val folders = admin.libraries(session)
        val scan = fullScanTask(session)
        if (guard.gate.value is AdminGate.Open) {
            libraries.value = folders
            fullScan.value = scan
        }
        if (LibrariesUiState(libraries = folders, fullScan = scan).anyScanning) SCAN_POLL_MS else null
    }

    val state: StateFlow<LibrariesUiState> = combine(frames, libraries, fullScan) { frame, libraries, fullScan ->
        if (frame.gate == AdminGate.Closed) LibrariesUiState(frame = frame) else LibrariesUiState(frame, libraries, fullScan)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibrariesUiState())

    fun refresh() = poll.kick()

    fun scanAll() {
        if (!state.value.canScanAll) return
        work.run("scan:all", { s -> admin.scanAll(s) }) {
            notify(AdminNotice(R.string.admin_scan_started_all))
            poll.kick()
        }
    }

    fun scanLibrary(libraryId: String) {
        val library = libraries.value.firstOrNull { it.id == libraryId } ?: return
        if (!state.value.canScan(library)) return
        work.run("scan:$libraryId", { s -> admin.scanLibrary(s, libraryId) }) {
            notify(AdminNotice(R.string.admin_scan_started, listOf(library.name), R.string.admin_untitled))
            poll.kick()
        }
    }

    override fun dropLoaded() {
        libraries.value = emptyList()
        fullScan.value = null
    }

    /**
     * The full scan's task, or null: the tasks part failing is not a load problem (the scan screen works without it), unless the
     * failure ends the sign-in, which must still close the gate.
     */
    private suspend fun fullScanTask(session: Session): ServerTask? = try {
        admin.tasks(session).firstOrNull { it.key == FULL_SCAN_TASK_KEY }
    } catch (e: CancellationException) {
        throw e
    } catch (e: AppError) {
        if (e.kind == AppError.Kind.UNAUTHORISED || e.kind == AppError.Kind.NOT_SIGNED_IN) throw e
        null
    }
}
