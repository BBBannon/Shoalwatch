package app.shoalwatch.ui.detail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.core.AppError
import app.shoalwatch.data.library.Freshness
import app.shoalwatch.data.library.ItemDetail
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.Library
import app.shoalwatch.data.library.Playstate
import app.shoalwatch.data.library.RefreshTarget
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.browse.ItemActions
import app.shoalwatch.ui.browse.Work
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * LOADING: nothing saved yet and the first fetch is running. GONE: the server no longer shows this item to this user.
 * ERROR: nothing saved and the fetch failed (an error state with Retry, never a spinner that no one will stop).
 */
enum class DetailPhase { LOADING, SIGNED_OUT, GONE, ERROR, READY }

data class DetailUiState(
    val phase: DetailPhase = DetailPhase.LOADING,
    val session: Session? = null,
    val item: ItemWithUserData? = null,
    val refreshing: Boolean = false,
    val banner: BannerKind? = null,
    val pulling: Boolean = false,
    val working: Set<Work> = emptySet(),
)

/** A film or an episode, for the ACTIVE session (restarted when it changes, so nothing of another account stays). */
@OptIn(ExperimentalCoroutinesApi::class)
class DetailViewModel(
    private val itemId: String,
    private val catalog: ServerCatalog,
    connections: ServerConnections,
    private val library: Library,
    playstate: Playstate,
) : ViewModel() {
    private val actions = ItemActions(playstate, viewModelScope) { state.value.session }

    /** Failure messages for a snackbar, each once. */
    val messages: Flow<String> = actions.failures

    private fun build(s: Session, d: ItemDetail): DetailUiState {
        val stale = d.freshness as? Freshness.Stale
        val phase = when {
            d.item != null -> DetailPhase.READY
            stale?.reason == AppError.Kind.SERVER_NOT_FOUND -> DetailPhase.GONE
            stale != null -> DetailPhase.ERROR
            else -> DetailPhase.LOADING
        }
        return DetailUiState(phase, s, d.item, d.freshness is Freshness.Refreshing, stale?.reason?.banner())
    }

    val state: StateFlow<DetailUiState> =
        combine(
            catalog.activeSession().flatMapLatest { s ->
                if (s == null) flowOf(DetailUiState(DetailPhase.SIGNED_OUT)) else flow {
                    emit(DetailUiState(DetailPhase.LOADING, s))
                    emitAll(library.item(s, itemId).map { build(s, it) })
                }
            },
            actions.working,
            actions.pulling,
        ) { st, working, pulling ->
            st.copy(working = working.forSession(st.session), pulling = pulling != null && pulling == st.session)
        }
            .stateIn(viewModelScope, SharingStarted.Eagerly, DetailUiState())

    init {
        viewModelScope.launch {
            connectionRegained(catalog, connections).collect { s -> actions.attempt(s) { library.refresh(s, RefreshTarget.Item(itemId)) } }
        }
    }

    /** Pull-to-refresh and a banner's Retry: always tries. */
    fun refresh() {
        val s = state.value.session ?: return
        actions.pull(s) { library.refresh(s, RefreshTarget.Item(itemId)) }
    }

    fun setPlayed(played: Boolean) {
        state.value.session?.let { actions.setPlayed(it, itemId, played) }
    }

    fun setFavourite(favourite: Boolean) {
        state.value.session?.let { actions.setFavourite(it, itemId, favourite) }
    }
}
