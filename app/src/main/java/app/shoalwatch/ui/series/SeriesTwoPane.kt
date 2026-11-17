package app.shoalwatch.ui.series

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.images.backdropArtwork
import app.shoalwatch.ui.components.BackBar
import app.shoalwatch.ui.components.BannerActions
import app.shoalwatch.ui.components.ExpandableText
import app.shoalwatch.ui.components.FreshnessBanner
import app.shoalwatch.ui.components.Gutter
import app.shoalwatch.ui.components.HeroBackdrop
import app.shoalwatch.ui.components.PaneHeading
import app.shoalwatch.ui.components.ReadableWidth
import app.shoalwatch.ui.downloads.DownloadControls
import app.shoalwatch.ui.present.displayName
import app.shoalwatch.ui.present.plainOverview
import app.shoalwatch.ui.present.rememberStrings

/** The left pane's width on a landscape tablet. */
private val AboutPaneWidth = 400.dp

/**
 * A series on a landscape tablet ([app.shoalwatch.ui.components.isTwoPane]). Left, 400dp, scrolling on its own: the picture (or a
 * Back bar), which stays put so Back is always first and always there, then "About the series", the title and facts, the
 * overview, the actions and the season chips. Right, the rest of the width (at most [ReadableWidth], centred): "Episodes", the
 * season header and the episodes in their own list, which the Continue Watching highlight scrolls. The banners and the More
 * sheet are the one-column page's.
 */
@Composable
internal fun SeriesTwoPane(state: SeriesUiState, actions: SeriesActions, download: DownloadControls?) {
    val strings = rememberStrings()
    val session = state.session
    val listState = rememberLazyListState()
    var sheetId by rememberSaveable(session) { mutableStateOf<String?>(null) }
    val series = state.series
    val title = series?.item?.displayName(strings).orEmpty()
    val banner = BannerActions(actions.onRefresh, { session?.let { actions.onSignIn(it.serverId) } }, { session?.let { actions.onReview(it.serverId) } })
    val overview = remember(series?.item?.overview) { series?.item?.overview?.let { plainOverview(it) }.orEmpty() }
    val heroArtwork = if (series != null && session != null) series.item.backdropArtwork(session) else null
    val failedEmpty = series == null && state.episodes.isEmpty() && state.banner != null
    // Items ahead of the first episode in the right pane: its heading, then the season header.
    val before = 1 + (if (state.seasons.isNotEmpty()) 1 else 0)

    var scrolled by rememberSaveable(state.highlightEpisodeId) { mutableStateOf(false) }
    LaunchedEffect(state.highlightEpisodeId, state.episodes, scrolled, before) {
        val at = state.episodes.indexOfFirst { it.item.id == state.highlightEpisodeId }
        if (!scrolled && at >= 0) {
            scrolled = true
            listState.scrollToItem(before + at)
        }
    }

    // A season chosen in the left pane starts the right one at its top (review M6 of Tasks 9-10); the first season shown does not
    // move the list, so the highlight's own scroll stands.
    var shownSeason by rememberSaveable { mutableStateOf(state.selectedSeasonId) }
    LaunchedEffect(state.selectedSeasonId) {
        val before = shownSeason
        shownSeason = state.selectedSeasonId
        if (before != null && before != state.selectedSeasonId) listState.scrollToItem(0)
    }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(AboutPaneWidth).fillMaxHeight()) {
            if (heroArtwork != null) HeroBackdrop(heroArtwork, title, actions.onBack) else BackBar(actions.onBack)
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PaneHeading(stringResource(R.string.pane_series_about))
                if (!failedEmpty) Header(state, title, strings, actions.onRefresh)
                if (!failedEmpty) state.banner?.let { FreshnessBanner(it, banner) }
                if (series != null) {
                    if (overview.isNotEmpty()) ExpandableText(overview, Modifier.padding(horizontal = Gutter))
                    SeriesActionsRow(state, actions, download)
                }
                if (state.seasons.isNotEmpty()) SeasonChips(state, actions)
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            LazyColumn(
                Modifier.widthIn(max = ReadableWidth).fillMaxSize(), state = listState,
                contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item(key = "episodes-heading") { PaneHeading(stringResource(R.string.pane_series_episodes), Modifier.statusBarsPadding()) }
                if (state.seasons.isNotEmpty()) item(key = "season-header") { SeasonHeader(state, actions) }
                episodes(state, actions, session, strings, banner, download) { sheetId = it }
            }
        }
    }
    EpisodeSheet(state, actions, sheetId) { sheetId = null }
}
