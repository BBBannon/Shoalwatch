package app.shoalwatch.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.core.AppError
import app.shoalwatch.data.discover.BrowseFilter
import app.shoalwatch.data.discover.Discover
import app.shoalwatch.data.discover.FilterOptions
import app.shoalwatch.data.library.Freshness
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.Library
import app.shoalwatch.data.library.ListShape
import app.shoalwatch.data.library.Sort
import app.shoalwatch.data.library.SortBy
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.lists.ListsPhase
import app.shoalwatch.ui.lists.phaseFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The letters of the bar, in order: "#" (names before A), then A to Z. */
val BROWSE_LETTERS: List<String> = listOf("#") + ('A'..'Z').map { it.toString() }

data class BrowseUiState(
    /** The profile everything below belongs to; null while nobody is signed in. */
    val session: Session? = null,
    /** Whether the library's shape (films, series...) is known: waiting, arrived, or why not. The list has its own phase. */
    val phase: ListsPhase = ListsPhase.LOADING,
    /** The library's name; empty when it has none (a folder). */
    val title: String = "",
    val filter: BrowseFilter = BrowseFilter(),
    /** The choices for each filter; null until read or when they could not be. */
    val options: FilterOptions? = null,
    val list: PagedState<ItemWithUserData> = PagedState(),
) {
    override fun toString() = "BrowseUiState(<redacted>)"
}

/**
 * A library's live filtered list (Milestone 11): at most one genre, studio, year, age rating and tag, and a starting letter
 * (A to Z, or "#"), in name order, a page at a time. Every change starts the list again (an answer to an older question is
 * dropped). The choices come from the library's own filter lists, each of which may be missing without harming the others.
 * [initial] is the genre or studio a Genres or Studios page opened this with.
 */
class BrowseViewModel(
    private val parentId: String,
    initial: BrowseFilter,
    catalog: ServerCatalog,
    private val library: Library,
    private val discover: Discover,
) : ViewModel() {
    private data class Head(
        val session: Session?, val phase: ListsPhase, val title: String, val shape: ListShape?, val filter: BrowseFilter, val options: FilterOptions?,
    )

    private val head = MutableStateFlow(Head(null, ListsPhase.LOADING, "", null, initial, null))
    private val items = PagedLoader<ItemWithUserData>(viewModelScope) { it.item.id }
    private val sort = Sort(SortBy.NAME, descending = false)

    val state: StateFlow<BrowseUiState> = combine(head, items.state) { h, list -> BrowseUiState(h.session, h.phase, h.title, h.filter, h.options, list) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, BrowseUiState(filter = initial))

    init {
        viewModelScope.launch {
            catalog.activeSession().collectLatest { s ->
                head.update { Head(s, if (s == null) ListsPhase.READY else ListsPhase.LOADING, "", null, it.filter, null) }
                if (s == null) items.restart(null) else open(s)
            }
        }
    }

    /** Works out what the library holds (the shape of its list), then reads the first page and the filter choices. */
    private suspend fun open(s: Session) {
        items.fail(ListsPhase.LOADING)
        val home = try {
            library.home(s).first { it.views.isNotEmpty() || it.viewsFreshness !is Freshness.Refreshing }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            head.update { if (it.session == s) it.copy(phase = phaseFor(e)) else it }
            items.fail(phaseFor(e))
            return
        }
        val view = home.views.firstOrNull { it.id == parentId }
        if (view == null && home.views.isEmpty() && home.viewsFreshness is Freshness.Stale) {
            // The libraries could not be read, so what this one holds is not known: say offline (or failed) rather than guess.
            val phase = phaseFor(AppError.of((home.viewsFreshness as Freshness.Stale).reason))
            head.update { if (it.session == s) it.copy(phase = phase) else it }
            items.fail(phase)
            return
        }
        val shape = view?.let { ListShape.forLibrary(it.kind) } ?: ListShape.DIRECT
        head.update { if (it.session == s) it.copy(phase = ListsPhase.READY, title = view?.name.orEmpty(), shape = shape) else it }
        start(s, shape, head.value.filter)
        val options = try {
            discover.filterOptions(s, parentId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        head.update { if (it.session == s) it.copy(options = options) else it }
    }

    private fun start(s: Session, shape: ListShape, filter: BrowseFilter) {
        if (head.value.session != s) return // the profile changed while the shape was being read
        items.restart { start -> discover.browse(s, parentId, shape, filter, sort, start) }
    }

    private fun change(f: (BrowseFilter) -> BrowseFilter) {
        val h = head.value
        val s = h.session ?: return
        val shape = h.shape ?: return
        val next = f(h.filter)
        if (next == h.filter) return
        head.update { it.copy(filter = next) }
        start(s, shape, next)
    }

    fun setGenre(id: String?) = change { it.copy(genreId = id) }
    fun setStudio(id: String?) = change { it.copy(studioId = id) }
    fun setYear(year: Int?) = change { it.copy(year = year) }
    fun setRating(rating: String?) = change { it.copy(rating = rating) }
    fun setTag(tag: String?) = change { it.copy(tag = tag) }

    /** A letter of the bar ("#" or A to Z); the one already chosen clears it. */
    fun setLetter(letter: String?) = change { it.copy(letter = letter?.takeIf { l -> l != it.letter }) }

    fun clearFilters() = change { BrowseFilter() }

    fun onEndVisible() = items.loadMore()

    /** Try again: the list again, and the library's shape too when that was what failed. */
    fun retry() {
        val h = head.value
        val s = h.session ?: return
        val shape = h.shape
        if (shape == null) {
            head.update { it.copy(phase = ListsPhase.LOADING) }
            viewModelScope.launch { open(s) }
        } else items.retry()
    }

    /** A later page failed: that page again. */
    fun retryMore() = items.retry()
}
