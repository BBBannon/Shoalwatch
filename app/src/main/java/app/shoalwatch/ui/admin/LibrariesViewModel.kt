package app.shoalwatch.ui.admin

import androidx.lifecycle.viewModelScope
import app.shoalwatch.R
import app.shoalwatch.data.admin.Admin
import app.shoalwatch.data.admin.AdminRights
import app.shoalwatch.data.admin.LibraryFolder
import app.shoalwatch.data.admin.ServerTask
import app.shoalwatch.data.admin.TaskState
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerConnections
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

    /** "Scan all libraries" is on unless the full scan is already running (and only when the screen can act): [ScanAll]'s rule. */
    val canScanAll: Boolean get() = canScanAll(frame, fullScan)

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
    private val scanAll = ScanAll(admin)

    private val poll = poller { session ->
        val folders = admin.libraries(session)
        val scan = scanAll.read(session)
        if (guard.gate.value is AdminGate.Open) {
            libraries.value = folders
            scanAll.keep(scan)
        }
        if (LibrariesUiState(libraries = folders, fullScan = scan).anyScanning) SCAN_POLL_MS else null
    }

    val state: StateFlow<LibrariesUiState> = combine(frames, libraries, scanAll.fullScan) { frame, libraries, fullScan ->
        if (frame.gate == AdminGate.Closed) LibrariesUiState(frame = frame) else LibrariesUiState(frame, libraries, fullScan)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LibrariesUiState())

    fun refresh() = poll.kick()

    fun scanAll() = scanAll.start(frames.value, work) {
        notify(ScanAll.STARTED)
        poll.kick()
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
        scanAll.drop()
    }
}
