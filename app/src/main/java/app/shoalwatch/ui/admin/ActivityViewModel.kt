package app.shoalwatch.ui.admin

import androidx.lifecycle.viewModelScope
import app.shoalwatch.data.admin.ActivityEntry
import app.shoalwatch.data.admin.ActivityPage
import app.shoalwatch.data.admin.Admin
import app.shoalwatch.data.admin.AdminRights
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerConnections
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/** Entries asked for per page. */
const val ACTIVITY_PAGE = 50
/** No more than this many entries are shown; beyond it the full log is in the dashboard. */
const val ACTIVITY_MAX_ENTRIES = 1_000

data class ActivityUiState(
    val frame: AdminFrame = AdminFrame(),
    val entries: List<ActivityEntry> = emptyList(),
    val loadingMore: Boolean = false,
    val endReached: Boolean = false,
    val limitReached: Boolean = false,
) {
    /** "Show older entries" is offered while there may be more and the cap is not reached. */
    val canLoadMore: Boolean get() = entries.isNotEmpty() && !endReached && !limitReached

    override fun toString() = "ActivityUiState(<redacted>)"
}

/**
 * The activity log, newest first: the first page on open and on Refresh (which starts again from the newest), older pages only
 * when asked ("Show older entries", never automatic, never retried), de-duplicated by id, at most [ACTIVITY_MAX_ENTRIES].
 */
class ActivityViewModel(
    catalog: ServerCatalog,
    rights: AdminRights,
    connections: ServerConnections,
    private val admin: Admin,
    clock: () -> Instant = Instant::now,
) : AdminScreenModel(catalog, rights, connections, clock) {
    private data class Log(
        val entries: List<ActivityEntry> = emptyList(),
        val nextStart: Int = 0,
        val endReached: Boolean = false,
        val loadingMore: Boolean = false,
        /** Bumped by every first-page load, so an older page asked for before it is dropped. */
        val generation: Int = 0,
    )

    private val log = MutableStateFlow(Log())

    private val poll = poller { session ->
        val page = admin.activity(session, 0, ACTIVITY_PAGE)
        if (guard.gate.value is AdminGate.Open) {
            log.update { Log(merge(emptyList(), page), page.nextStartIndex, page.endReached, loadingMore = false, generation = it.generation + 1) }
        }
        null
    }

    val state: StateFlow<ActivityUiState> = combine(frames, log) { frame, log ->
        if (frame.gate == AdminGate.Closed) {
            ActivityUiState(frame = frame)
        } else {
            ActivityUiState(frame, log.entries, log.loadingMore, log.endReached, log.entries.size >= ACTIVITY_MAX_ENTRIES)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ActivityUiState())

    /** Starts again from the newest entry. */
    fun refresh() = poll.kick()

    fun loadMore() {
        val gate = frames.value.gate as? AdminGate.Open ?: return
        val current = log.value
        if (current.loadingMore || current.endReached || current.entries.isEmpty() || current.entries.size >= ACTIVITY_MAX_ENTRIES) return
        if (!gate.online) {
            work.fail(AdminProblem.OFFLINE)
            return
        }
        log.value = current.copy(loadingMore = true)
        work.dismissProblem()
        viewModelScope.launch {
            try {
                val page = admin.activity(gate.session, current.nextStart, ACTIVITY_PAGE)
                log.update {
                    if (guard.gate.value !is AdminGate.Open || it.generation != current.generation) it.copy(loadingMore = false)
                    else it.copy(entries = merge(it.entries, page), nextStart = page.nextStartIndex, endReached = page.endReached, loadingMore = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.update { it.copy(loadingMore = false) }
                guard.problemFor(e)?.let(work::fail)
            }
        }
    }

    override fun dropLoaded() {
        log.value = Log()
    }

    private companion object {
        /** [earlier] plus the page's entries not already there (first wins), newest first, at most the cap. */
        fun merge(earlier: List<ActivityEntry>, page: ActivityPage): List<ActivityEntry> {
            val seen = earlier.mapTo(HashSet()) { it.id }
            val added = page.entries.filter { seen.add(it.id) }
            return (earlier + added).sortedByDescending { it.at }.take(ACTIVITY_MAX_ENTRIES)
        }
    }
}
