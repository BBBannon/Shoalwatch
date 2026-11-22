package app.shoalwatch.ui.series

import androidx.compose.runtime.CompositionLocalProvider
import app.shoalwatch.ui.browse.BulkAction
import app.shoalwatch.ui.browse.BulkHost
import app.shoalwatch.ui.browse.BulkItem
import app.shoalwatch.ui.browse.LocalSelection
import app.shoalwatch.ui.browse.SelectionChrome
import app.shoalwatch.ui.browse.rememberSelection
import app.shoalwatch.ui.browse.selectionControls
import app.shoalwatch.ui.components.SelectionBar
import app.shoalwatch.ui.present.sheetTitle
import app.shoalwatch.ui.components.OverlayBackButton
import app.shoalwatch.ui.components.refreshAction
import app.shoalwatch.ui.components.PullRefresh
import app.shoalwatch.ui.components.BarHeight
import app.shoalwatch.ui.components.BackBar
import app.shoalwatch.ui.components.ScrollingColumn
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.ui.discover.ExtrasBlock
import app.shoalwatch.ui.discover.ExtrasControls
import app.shoalwatch.data.images.backdropArtwork
import app.shoalwatch.ui.components.BannerActions
import app.shoalwatch.ui.components.CollectMessages
import app.shoalwatch.ui.components.EmptyState
import app.shoalwatch.ui.components.ExpandableText
import app.shoalwatch.ui.components.FreshnessBanner
import app.shoalwatch.ui.components.Gutter
import app.shoalwatch.ui.components.HeroBackdrop
import app.shoalwatch.ui.components.ReadableBox
import app.shoalwatch.ui.components.RefreshLine
import app.shoalwatch.ui.components.isTwoPane
import app.shoalwatch.ui.components.SignedOutNotice
import app.shoalwatch.ui.downloads.DownloadControls
import app.shoalwatch.ui.present.Destination
import app.shoalwatch.ui.present.displayName
import app.shoalwatch.ui.present.plainOverview
import app.shoalwatch.ui.present.rememberStrings
import kotlinx.coroutines.flow.Flow

/** What the series screen can ask for. The route wires these to the ViewModel and to navigation. */
class SeriesActions(
    val onBack: () -> Unit,
    val onPickSeason: (seasonId: String) -> Unit,
    val onRefresh: () -> Unit,
    val onOpen: (Destination) -> Unit,
    val onSignIn: (serverId: String) -> Unit,
    val onReview: (serverId: String) -> Unit,
    val onOpenServers: () -> Unit,
    val onSetSeriesPlayed: (played: Boolean) -> Unit,
    val onSetSeriesFavourite: (favourite: Boolean) -> Unit,
    val onSetSeasonPlayed: (seasonId: String, played: Boolean) -> Unit,
    val onSetPlayed: (itemId: String, played: Boolean) -> Unit,
    val onSetFavourite: (itemId: String, favourite: Boolean) -> Unit,
    /** Milestone 10: "Manage" for an administrator (identify, pictures, details, refresh, delete); null hides it. */
    val onManage: (() -> Unit)? = null,
)

/**
 * A series: a wide picture and title at the top that scrolls away (a slim bar with the title and Back takes its place), the
 * overview, "Mark series as watched" and favourite, the seasons as chips, and the episodes of the chosen season. Content is
 * capped at a readable width and centred on a tablet; from [app.shoalwatch.ui.components.TwoPaneWidth] (a landscape tablet) it is
 * two panes ([SeriesTwoPane]).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(
    state: SeriesUiState,
    actions: SeriesActions,
    messages: Flow<String>,
    snackbar: SnackbarHostState,
    download: DownloadControls? = null,
    bulk: BulkHost? = null,
    extras: ExtrasControls? = null,
) {
    CollectMessages(messages, snackbar, state.session)
    val strings = rememberStrings()
    val selection = bulk?.let { rememberSelection(state.session) }
    val listed = remember(state.episodes, strings) {
        state.episodes.associate { it.item.id to BulkItem(it.item.id, it.item.sheetTitle(strings), it.item.kind, it.item.seriesId) }
    }
    if (selection != null && bulk != null) SelectionChrome(selection, bulk, listed, snackbar)
    var moreId by rememberSaveable(state.session) { mutableStateOf<String?>(null) }
    EpisodeSheet(state, actions, moreId) { moreId = null }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        // The navigation bar's inset is the Scaffold's: content ends above the bar and the snackbar sits above it. The status bar's
        // is not, because the picture runs up under it.
        contentWindowInsets = WindowInsets.navigationBars,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.phase) {
                SeriesPhase.SIGNED_OUT -> ScrollingColumn {
                    BackBar(actions.onBack)
                    SignedOutNotice(actions.onOpenServers)
                }
                SeriesPhase.GONE -> ScrollingColumn {
                    BackBar(actions.onBack)
                    EmptyState(stringResource(R.string.gone_title), stringResource(R.string.gone_body), actionLabel = stringResource(R.string.cd_back), onAction = actions.onBack)
                }
                else -> {
                    val twoPane = isTwoPane()
                    CompositionLocalProvider(LocalSelection provides selection) {
                        PullRefresh(isRefreshing = state.pulling, onRefresh = actions.onRefresh, modifier = Modifier.fillMaxSize(), belowStatusBar = true) {
                            if (twoPane) SeriesTwoPane(state, actions, download, extras) else SeriesContent(state, actions, download, extras)
                        }
                    }
                    RefreshLine(state.refreshing && !state.pulling, Modifier.statusBarsPadding().align(Alignment.TopCenter))
                    if (selection != null && bulk != null && selection.active) {
                        SelectionBar(selectionControls(selection, bulk, listed, BulkAction.forBrowsing) { moreId = it }, Modifier.align(Alignment.TopCenter))
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesContent(state: SeriesUiState, actions: SeriesActions, download: DownloadControls?, extras: ExtrasControls?) {
    val strings = rememberStrings()
    val session = state.session
    val listState = rememberLazyListState()
    var sheetId by rememberSaveable(session) { mutableStateOf<String?>(null) }
    val series = state.series
    val title = series?.item?.displayName(strings).orEmpty()
    val banner = BannerActions(actions.onRefresh, { session?.let { actions.onSignIn(it.serverId) } }, { session?.let { actions.onReview(it.serverId) } })
    val showBar by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val overview = remember(series?.item?.overview) { series?.item?.overview?.let { plainOverview(it) }.orEmpty() }
    // A picture only when there is a real backdrop. Without one the page starts with a plain Back bar and the poster beside the title.
    val heroArtwork = if (series != null && session != null) series.item.backdropArtwork(session) else null
    // Nothing saved and the load failed: ONE message with its button, instead of a banner above an empty list.
    val failedEmpty = series == null && state.episodes.isEmpty() && state.banner != null
    // Items ahead of the first episode: hero, banner, overview, actions, then the chips and the season header.
    val before = 1 + (if (state.banner != null) 1 else 0) + (if (overview.isNotEmpty()) 1 else 0) + (if (series != null) 1 else 0) + (if (state.seasons.isNotEmpty()) 2 else 0)

    // Coming from an episode in Continue Watching: bring that episode into view once, when its season has loaded.
    var scrolled by rememberSaveable(state.highlightEpisodeId) { mutableStateOf(false) }
    LaunchedEffect(state.highlightEpisodeId, state.episodes, scrolled, before) {
        val at = state.episodes.indexOfFirst { it.item.id == state.highlightEpisodeId }
        if (!scrolled && at >= 0) {
            scrolled = true
            listState.scrollToItem(before + at)
        }
    }

    Box(Modifier.fillMaxSize()) {
        ReadableBox {
            LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item(key = "hero") {
                    Column {
                        if (heroArtwork != null) HeroBackdrop(heroArtwork, title, actions.onBack) else BackBar(actions.onBack)
                        // Nothing saved and the load failed: the error state below is all there is, so no blocks that look still loading.
                        if (!failedEmpty) Header(state, title, strings, actions.onRefresh)
                    }
                }
                if (!failedEmpty) state.banner?.let { kind -> item(key = "banner") { FreshnessBanner(kind, banner) } }
                if (series != null) {
                    if (overview.isNotEmpty()) item(key = "overview") { ExpandableText(overview, Modifier.padding(horizontal = Gutter)) }
                    item(key = "actions") { SeriesActionsRow(state, actions, download) }
                }
                if (state.seasons.isNotEmpty()) {
                    item(key = "seasons") { SeasonChips(state, actions) }
                    item(key = "season-header") { SeasonHeader(state, actions) }
                }
                episodes(state, actions, session, strings, banner, download) { sheetId = it }
                if (extras != null && session != null && series != null) item(key = "extras") { ExtrasBlock(extras, session) }
            }
        }
        AnimatedVisibility(visible = showBar, modifier = Modifier.align(Alignment.TopStart)) {
            // The status bar's height plus a normal bar, not more. First in a screen reader's order, so Back is reached first.
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).statusBarsPadding().heightIn(min = BarHeight).padding(horizontal = 4.dp)
                    .semantics { isTraversalGroup = true; traversalIndex = -1f },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OverlayBackButton(actions.onBack)
                Text(
                    title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.refreshAction(actions.onRefresh).semantics { heading() },
                )
            }
        }
    }

    EpisodeSheet(state, actions, sheetId) { sheetId = null }
}
