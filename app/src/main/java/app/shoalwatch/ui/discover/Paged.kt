package app.shoalwatch.ui.discover

import app.shoalwatch.data.discover.DiscoverLimits
import app.shoalwatch.data.library.Page
import app.shoalwatch.ui.lists.ListsPhase
import app.shoalwatch.ui.lists.phaseFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A live list read a page at a time (Milestone 11 discovery screens). [phase] is for the first page only: later pages that fail
 * leave what is there and set [moreFailed] (Try again loads the next page again).
 */
data class PagedState<T>(
    val phase: ListsPhase = ListsPhase.LOADING,
    val items: List<T> = emptyList(),
    val total: Int? = null,
    val canLoadMore: Boolean = false,
    val loadingMore: Boolean = false,
    val moreFailed: Boolean = false,
) {
    override fun toString() = "PagedState(<redacted>)"
}

/**
 * The paging every discovery list shares: the first page, then more as the end comes into view. Items are kept once each by
 * [key]; a start index beyond [DiscoverLimits.MAX_START] or a page that moved nowhere ends the list. A newer [restart] cancels
 * whatever is running, so a late answer to an old question can never land in a new list.
 */
class PagedLoader<T>(private val scope: CoroutineScope, private val key: (T) -> Any) {
    private val body = MutableStateFlow(PagedState<T>())
    val state: StateFlow<PagedState<T>> = body.asStateFlow()

    private var fetch: (suspend (Int) -> Page<T>)? = null
    private var nextStart = 0
    private var job: Job? = null
    private var generation = 0

    /** Starts over with [fetch] (null: nothing to read) and shows the first page's phase meanwhile. */
    fun restart(fetch: (suspend (Int) -> Page<T>)?) {
        job?.cancel()
        generation++
        this.fetch = fetch
        nextStart = 0
        body.value = PagedState(phase = if (fetch == null) ListsPhase.READY else ListsPhase.LOADING)
        if (fetch != null) launchFirst()
    }

    /** Stops without reading anything and shows [phase] (for a problem found before any read). */
    fun fail(phase: ListsPhase) {
        job?.cancel()
        generation++
        fetch = null
        body.value = PagedState(phase = phase)
    }

    /** Try again: the first page again when it failed, the next page again when that failed. */
    fun retry() {
        val s = body.value
        if (fetch == null) return
        if (s.items.isEmpty() || s.phase != ListsPhase.READY) {
            job?.cancel()
            generation++
            nextStart = 0
            body.value = PagedState(phase = ListsPhase.LOADING)
            launchFirst()
        } else {
            body.update { it.copy(moreFailed = false) }
            loadMore()
        }
    }

    /** The end is in view: the next page, when there is one and nothing is running or has just failed. */
    fun loadMore() {
        val s = body.value
        val f = fetch ?: return
        if (s.phase != ListsPhase.READY || !s.canLoadMore || s.loadingMore || s.moreFailed) return
        val mine = generation
        val start = nextStart
        body.update { it.copy(loadingMore = true) }
        job = scope.launch {
            try {
                val page = f(start)
                if (mine == generation) accept(page, start, append = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mine == generation) body.update { it.copy(loadingMore = false, moreFailed = true) }
            }
        }
    }

    private fun launchFirst() {
        val f = fetch ?: return
        val mine = generation
        job = scope.launch {
            try {
                val page = f(0)
                if (mine == generation) accept(page, 0, append = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (mine == generation) body.value = PagedState(phase = phaseFor(e))
            }
        }
    }

    private fun accept(page: Page<T>, start: Int, append: Boolean) {
        val moved = page.nextStartIndex > start
        nextStart = page.nextStartIndex
        val more = !page.endReached && moved && nextStart <= DiscoverLimits.MAX_START
        body.update { old ->
            val joined = if (append) old.items + page.items else page.items
            PagedState(ListsPhase.READY, joined.distinctBy(key), page.total, more, loadingMore = false, moreFailed = false)
        }
    }
}
