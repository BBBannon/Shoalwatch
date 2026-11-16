package app.shoalwatch.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.core.AppError
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.Library
import app.shoalwatch.data.library.SearchResult
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.browse.reprobeIfOffline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

enum class SearchPhase { SIGNED_OUT, IDLE, SEARCHING, RESULTS, EMPTY, ERROR }

/**
 * [query] is exactly what is in the box (not debounced). Results are grouped; [offline] says they are only what is saved on the
 * phone. [error] is an `AppError.userMessage` (nothing from the server). Nothing here is ever saved: a new instance starts empty.
 */
data class SearchUiState(
    val phase: SearchPhase = SearchPhase.IDLE,
    val session: Session? = null,
    val query: String = "",
    val films: List<ItemWithUserData> = emptyList(),
    val series: List<ItemWithUserData> = emptyList(),
    val episodes: List<ItemWithUserData> = emptyList(),
    val offline: Boolean = false,
    val error: String? = null,
) {
    /** One letter typed: not enough to search yet. */
    val tooShort: Boolean get() = query.trim().length in 1 until MIN_QUERY
}

const val MIN_QUERY = 2
const val SEARCH_DEBOUNCE_MS = 300L

/**
 * Search as you type: 300 ms after the last key, at least two letters, a newer search cancels the one before it. Offline it
 * searches what is saved and says so. The query lives only here (never in saved state, never on disk).
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class SearchViewModel(
    private val catalog: ServerCatalog,
    private val library: Library,
    private val connections: ServerConnections,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val retries = MutableStateFlow(0)

    private sealed interface Outcome {
        data object Idle : Outcome
        data object Searching : Outcome
        data class Done(val result: SearchResult) : Outcome
        data class Failed(val message: String) : Outcome
    }

    private fun outcomes(s: Session) =
        combine(
            query.debounce { if (it.trim().length < MIN_QUERY) 0L else SEARCH_DEBOUNCE_MS }.map { it.trim() }.distinctUntilChanged(),
            retries,
        ) { term, retry -> term to retry }
            .flatMapLatest { (term, _) ->
                if (term.length < MIN_QUERY) flowOf<Outcome>(Outcome.Idle) else flow<Outcome> {
                    emit(Outcome.Searching)
                    emit(
                        try {
                            Outcome.Done(library.search(s, term))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: AppError) {
                            Outcome.Failed(e.userMessage)
                        } catch (e: Exception) {
                            Outcome.Failed(AppError.of(AppError.Kind.UNKNOWN).userMessage)
                        },
                    )
                }
            }

    val state: StateFlow<SearchUiState> =
        combine(
            catalog.activeSession().distinctUntilChanged().flatMapLatest { s ->
                // Whatever one session typed is gone for the next: the box starts empty and nothing is searched until they type.
                query.value = ""
                if (s == null) flowOf<Pair<Session?, Outcome>>(null to Outcome.Idle) else flow<Pair<Session?, Outcome>> {
                    emit(s to Outcome.Idle)
                    emitAll(outcomes(s).map { s to it })
                }
            },
            query,
        ) { (session, outcome), q -> toState(session, outcome, q) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState())

    private fun toState(session: Session?, outcome: Outcome, q: String): SearchUiState {
        if (session == null) return SearchUiState(SearchPhase.SIGNED_OUT, query = q)
        return when (outcome) {
            Outcome.Idle -> SearchUiState(SearchPhase.IDLE, session, q)
            Outcome.Searching -> SearchUiState(SearchPhase.SEARCHING, session, q)
            is Outcome.Failed -> SearchUiState(SearchPhase.ERROR, session, q, error = outcome.message)
            is Outcome.Done -> {
                val items = outcome.result.items
                val films = items.filter { it.item.kind == ItemKind.MOVIE }
                val series = items.filter { it.item.kind == ItemKind.SERIES }
                val episodes = items.filter { it.item.kind == ItemKind.EPISODE }
                val empty = films.isEmpty() && series.isEmpty() && episodes.isEmpty()
                SearchUiState(if (empty) SearchPhase.EMPTY else SearchPhase.RESULTS, session, q, films, series, episodes, outcome.result.offline)
            }
        }
    }

    fun onQueryChange(text: String) {
        query.value = text.take(MAX_QUERY)
    }

    fun clear() {
        query.value = ""
    }

    /** Search again for the same words (after an error). */
    fun retry() {
        state.value.session?.let { connections.reprobeIfOffline(it) }
        retries.update { it + 1 }
    }
}

private const val MAX_QUERY = 200
