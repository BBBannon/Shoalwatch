package app.shoalwatch.ui.series

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.core.AppError
import app.shoalwatch.data.library.Freshness
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.Library
import app.shoalwatch.data.library.Playstate
import app.shoalwatch.data.library.RefreshTarget
import app.shoalwatch.data.library.SeriesDetail
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.browse.ItemActions
import app.shoalwatch.ui.browse.Work
import app.shoalwatch.ui.browse.connectionRegained
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
import kotlinx.coroutines.launch

enum class SeriesPhase { LOADING, SIGNED_OUT, GONE, READY }

/**
 * A series with its seasons and the episodes of the selected season, for one session ([session] owns every item below).
 * [selectedSeasonId] is the user's pick if there is one, else the library's choice (the season of the next unwatched episode).
 */
data class SeriesUiState(
    val phase: SeriesPhase = SeriesPhase.LOADING,
    val session: Session? = null,
    val series: ItemWithUserData? = null,
    val seasons: List<ItemWithUserData> = emptyList(),
    val episodes: List<ItemWithUserData> = emptyList(),
    val selectedSeasonId: String? = null,
    /** The episode the user came from (Continue Watching), picked out in the list. */
    val highlightEpisodeId: String? = null,
    val refreshing: Boolean = false,
    val banner: BannerKind? = null,
    val pulling: Boolean = false,
    val working: Set<Work> = emptySet(),
) {
    val seriesPlayed: Boolean get() = series?.userData?.played == true
    val seriesFavourite: Boolean get() = series?.userData?.favourite == true
    val selectedSeason: ItemWithUserData? get() = seasons.firstOrNull { it.item.id == selectedSeasonId }
}

private const val KEY_SEASON = "season"

/**
 * The series screen. The season the user picks is kept in [saved] (an id, nothing else), so it survives rotation and the app
 * being recreated; it beats the library's own default for as long as the screen lives, so marking an episode watched never
 * jumps seasons, and a refresh asks for the same season. Everything is for the ACTIVE session and restarts when it changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SeriesViewModel(
    private val seriesId: String,
    private val routeSeasonId: String?,
    private val highlightEpisodeId: String?,
    private val saved: SavedStateHandle,
    private val catalog: ServerCatalog,
    connections: ServerConnections,
    private val library: Library,
    playstate: Playstate,
) : ViewModel() {
    private val actions = ItemActions(playstate, viewModelScope)
    private val pulling = MutableStateFlow(false)
    private val picked: StateFlow<String?> = saved.getStateFlow<String?>(KEY_SEASON, null)

    /** Failure messages for a snackbar, each once. */
    val messages: Flow<String> = actions.failures

    private fun forSession(s: Session): Flow<SeriesUiState> =
        picked.map { it ?: routeSeasonId }.distinctUntilChanged().flatMapLatest { season ->
            library.series(s, seriesId, season).map { build(s, it) }
        }

    private fun build(s: Session, d: SeriesDetail): SeriesUiState {
        val stale = d.freshness as? Freshness.Stale
        val gone = d.series == null && stale?.reason == AppError.Kind.SERVER_NOT_FOUND
        return SeriesUiState(
            phase = if (gone) SeriesPhase.GONE else SeriesPhase.READY, session = s, series = d.series, seasons = d.seasons, episodes = d.episodes,
            selectedSeasonId = picked.value ?: d.selectedSeasonId, highlightEpisodeId = highlightEpisodeId,
            refreshing = d.freshness is Freshness.Refreshing, banner = stale?.reason?.banner(),
        )
    }

    val state: StateFlow<SeriesUiState> =
        combine(
            catalog.activeSession().flatMapLatest { s ->
                if (s == null) flowOf(SeriesUiState(SeriesPhase.SIGNED_OUT)) else flow {
                    emit(SeriesUiState(SeriesPhase.LOADING, s, highlightEpisodeId = highlightEpisodeId))
                    emitAll(forSession(s))
                }
            },
            actions.working,
            pulling,
        ) { st, working, pull -> st.copy(working = working, pulling = pull) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, SeriesUiState())

    init {
        viewModelScope.launch { connectionRegained(catalog, connections).collect { library.refresh(it, refreshTarget()) } }
    }

    private fun refreshTarget() = RefreshTarget.Series(seriesId, picked.value ?: routeSeasonId)

    /** The user chose a season chip: it sticks. */
    fun pickSeason(seasonId: String) {
        saved[KEY_SEASON] = seasonId
    }

    /** Pull-to-refresh and a banner's Retry: always tries, for the season on screen. */
    fun refresh() {
        val s = state.value.session ?: return
        viewModelScope.launch {
            pulling.value = true
            try {
                library.refresh(s, refreshTarget())
            } finally {
                pulling.value = false
            }
        }
    }

    fun setSeriesPlayed(played: Boolean) = setPlayed(seriesId, played)

    fun setSeasonPlayed(seasonId: String, played: Boolean) = setPlayed(seasonId, played)

    fun setPlayed(itemId: String, played: Boolean) {
        state.value.session?.let { actions.setPlayed(it, itemId, played) }
    }

    fun setSeriesFavourite(favourite: Boolean) = setFavourite(seriesId, favourite)

    fun setFavourite(itemId: String, favourite: Boolean) {
        state.value.session?.let { actions.setFavourite(it, itemId, favourite) }
    }
}
