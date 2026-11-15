package app.shoalwatch.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.core.AppError
import app.shoalwatch.data.library.Freshness
import app.shoalwatch.data.library.ItemFilter
import app.shoalwatch.data.library.Library
import app.shoalwatch.data.library.LibraryKind
import app.shoalwatch.data.library.ListShape
import app.shoalwatch.data.library.Listing
import app.shoalwatch.data.library.Playstate
import app.shoalwatch.data.library.RefreshTarget
import app.shoalwatch.data.library.Sort
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.browse.FAILED_LOCALLY
import app.shoalwatch.ui.browse.ItemActions
import app.shoalwatch.ui.browse.connectionRegained
import app.shoalwatch.ui.browse.forSession
import app.shoalwatch.ui.present.BannerKind
import app.shoalwatch.ui.present.banner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A library, collection or folder ([parentId]) as a continuously scrolling grid.
 *
 * The whole screen is for the ACTIVE session and restarts when it changes, so nothing of another account stays on screen. The
 * shape comes from the library's kind (`ListShape.forLibrary`), and the very same `(parentId, shape, sort, filter)` tuple is used
 * for the list, for loading more and for refreshing. Sort and filter live in [BrowseMemory] so they are remembered per library.
 *
 * Paging: the screen reports whether the end of the grid is within about two screens ([onEndVisible]). More is loaded when
 * there is more AND nothing is running AND the last attempt did not fail AND (the list is empty, or the end is in view); and
 * again when freshness returns to `Fresh` with the end still in view. A failure stops the chain until Retry (a refresh).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryGridViewModel(
    private val parentId: String,
    private val catalog: ServerCatalog,
    connections: ServerConnections,
    private val library: Library,
    playstate: Playstate,
    private val memory: BrowseMemory,
) : ViewModel() {
    private val actions = ItemActions(playstate, viewModelScope, connections) { state.value.session }
    private val endVisible = MutableStateFlow(false)

    /** The sessions whose page load is running (main thread only): one session never runs two beside each other, and never holds up another's. */
    private val loadingMore = HashSet<Session>()

    /** The session whose last page load failed locally with nothing yet to show: the grid says so with Retry instead of skeleton cards. */
    private val loadFailedFor = MutableStateFlow<Session?>(null)

    /** Failure messages for a snackbar, each once. */
    val messages: Flow<String> = actions.failures

    /** What the library says about [parentId]: its shape and name, or still unknown. */
    private sealed interface Resolved {
        data class Pending(val banner: BannerKind?) : Resolved
        data class Known(val shape: ListShape, val kind: LibraryKind?, val name: String?) : Resolved
    }

    private fun resolve(s: Session): Flow<Resolved> = library.home(s).map { home ->
        val view = home.views.firstOrNull { it.id == parentId }
        when {
            view != null -> Resolved.Known(ListShape.forLibrary(view.kind), view.kind, view.name)
            // The libraries themselves are not known yet (or could not be loaded): not a folder, so ask for nothing.
            home.views.isEmpty() && home.viewsFreshness !is Freshness.Fresh -> Resolved.Pending((home.viewsFreshness as? Freshness.Stale)?.reason?.banner())
            else -> Resolved.Known(ListShape.DIRECT, null, null)
        }
    }.distinctUntilChanged()

    private fun title(s: Session, r: Resolved.Known): Flow<String> =
        if (r.name != null) flowOf(r.name) else library.item(s, parentId).map { it.item?.item?.name.orEmpty() }.distinctUntilChanged()

    private fun forSession(s: Session): Flow<LibraryGridUiState> = resolve(s).flatMapLatest { r ->
        when (r) {
            is Resolved.Pending -> flowOf(LibraryGridUiState(GridPhase.LOADING, s, banner = r.banner))
            is Resolved.Known -> memory.choice(s, parentId).flatMapLatest { c ->
                combine(library.children(s, parentId, r.shape, c.sort, c.filter), title(s, r)) { listing, title ->
                    build(s, r, title, c, listing)
                }
            }
        }
    }

    private fun build(s: Session, r: Resolved.Known, title: String, c: SortFilter, l: Listing): LibraryGridUiState {
        val stale = l.freshness as? Freshness.Stale
        val gone = l.items.isEmpty() && stale?.reason == AppError.Kind.SERVER_NOT_FOUND
        return LibraryGridUiState(
            phase = if (gone) GridPhase.GONE else GridPhase.READY, session = s, title = title, kind = r.kind, shape = r.shape,
            sort = c.sort, filter = c.filter, items = l.items, total = l.total,
            refreshing = l.freshness is Freshness.Refreshing, fresh = l.freshness is Freshness.Fresh,
            banner = stale?.reason?.banner(), canLoadMore = l.canLoadMore,
        )
    }

    val state: StateFlow<LibraryGridUiState> =
        combine(
            catalog.activeSession().flatMapLatest { s ->
                if (s == null) flowOf(LibraryGridUiState(GridPhase.SIGNED_OUT)) else flow {
                    emit(LibraryGridUiState(GridPhase.LOADING, s))
                    emitAll(actions.guard(s, forSession(s)) { LibraryGridUiState(GridPhase.READY, s, banner = FAILED_LOCALLY.reason.banner()) })
                }
            },
            actions.working,
            actions.pulling,
            loadFailedFor,
        ) { st, working, pulling, loadFailed ->
            // A failed page load that left nothing on screen is shown like any other failed load (error state with Retry).
            val banner = st.banner ?: FAILED_LOCALLY.reason.banner().takeIf { st.session != null && st.session == loadFailed && st.items.isEmpty() }
            st.copy(working = working.forSession(st.session), pulling = st.session?.let { it in pulling } == true, banner = banner)
        }
            .stateIn(viewModelScope, SharingStarted.Eagerly, LibraryGridUiState())

    private data class PagingInput(
        val session: Session?, val shape: ListShape?, val sort: Sort, val filter: ItemFilter,
        val count: Int, val canLoadMore: Boolean, val fresh: Boolean, val endVisible: Boolean,
    )

    init {
        viewModelScope.launch {
            combine(state, endVisible) { st, end -> PagingInput(st.session, st.shape, st.sort, st.filter, st.items.size, st.canLoadMore, st.fresh, end) }
                .distinctUntilChanged()
                .collect { p -> if (p.canLoadMore && p.fresh && (p.count == 0 || p.endVisible)) startLoadMore() }
        }
        viewModelScope.launch { connectionRegained(catalog, connections).collect { refreshQuietly() } }
    }

    /** The grid reports that the end is within about two screens of what is showing (or is not). */
    fun onEndVisible(visible: Boolean) {
        endVisible.value = visible
    }

    private fun startLoadMore() {
        val st = state.value
        val s = st.session ?: return
        val shape = st.shape ?: return
        if (!loadingMore.add(s)) return
        viewModelScope.launch {
            try {
                val ok = actions.attempt(s) { library.loadMore(s, parentId, shape, st.sort, st.filter) }
                if (ok) loadFailedFor.compareAndSet(s, null) else loadFailedFor.value = s
            } finally {
                loadingMore.remove(s)
            }
        }
    }

    private fun target(st: LibraryGridUiState): RefreshTarget.Children? =
        st.shape?.let { RefreshTarget.Children(parentId, it, st.sort, st.filter) }

    private suspend fun refreshQuietly() {
        val st = state.value
        val s = st.session ?: return
        target(st)?.let { actions.attempt(s) { library.refresh(s, it) } }
    }

    /**
     * Pull-to-refresh and a banner's Retry: always tries (it bypasses the failure cooldown). While the shape is not known (Home's
     * libraries are not saved yet, or reading this screen failed locally) what is refreshed is the list of libraries, where the
     * shape comes from; like every user refresh it re-probes an offline server first and brings a failed screen back.
     */
    fun refresh() {
        val st = state.value
        val s = st.session ?: return
        val t: RefreshTarget = target(st) ?: RefreshTarget.Home
        loadFailedFor.value = null
        actions.pull(s) { library.refresh(s, t) }
    }

    fun setSort(sort: Sort) = change { it.copy(sort = sort) }

    fun setFilter(filter: ItemFilter) = change { it.copy(filter = filter) }

    fun clearFilters() = change { it.copy(filter = ItemFilter()) }

    private fun change(f: (SortFilter) -> SortFilter) {
        state.value.session?.let { memory.choice(it, parentId).update(f) }
    }

    fun setPlayed(itemId: String, played: Boolean) {
        state.value.session?.let { actions.setPlayed(it, itemId, played) }
    }

    /** After a favourite change in a list filtered by favourites, that list is refreshed so the item appears or goes. */
    fun setFavourite(itemId: String, favourite: Boolean) {
        val st = state.value
        val s = st.session ?: return
        actions.setFavourite(s, itemId, favourite) { if (st.filter.favouritesOnly) target(st)?.let { library.refresh(s, it) } }
    }
}
