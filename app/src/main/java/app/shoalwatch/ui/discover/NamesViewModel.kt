package app.shoalwatch.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.data.discover.Discover
import app.shoalwatch.data.discover.Named
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Which named things a names page lists. */
enum class NamesKind { GENRES, STUDIOS }

data class NamesUiState(
    /** The profile the list belongs to; null while nobody is signed in. */
    val session: Session? = null,
    val kind: NamesKind = NamesKind.GENRES,
    val names: PagedState<Named> = PagedState(),
) {
    override fun toString() = "NamesUiState(<redacted>)"
}

/**
 * The genres or studios of one library (Milestone 11, live), a page at a time as the end comes into view. Each opens the
 * filtered list. Restarts when the profile changes and nothing of the old one lingers.
 */
class NamesViewModel(private val parentId: String, private val kind: NamesKind, catalog: ServerCatalog, private val discover: Discover) : ViewModel() {
    private val session = MutableStateFlow<Session?>(null)
    private val names = PagedLoader<Named>(viewModelScope) { it.id }

    val state: StateFlow<NamesUiState> = combine(session, names.state) { s, n -> NamesUiState(s, kind, n) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, NamesUiState(kind = kind))

    init {
        viewModelScope.launch {
            catalog.activeSession().collectLatest { s ->
                session.value = s
                names.restart(s?.let { fetcher(it) })
            }
        }
    }

    private fun fetcher(s: Session): suspend (Int) -> app.shoalwatch.data.library.Page<Named> = when (kind) {
        NamesKind.GENRES -> { start -> discover.genres(s, parentId, start) }
        NamesKind.STUDIOS -> { start -> discover.studios(s, parentId, start) }
    }

    fun onEndVisible() = names.loadMore()

    fun retry() = names.retry()
}
