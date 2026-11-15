package app.shoalwatch.ui.series

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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.images.backdropArtwork
import app.shoalwatch.data.images.primaryArtwork
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.ui.browse.ActionKind
import app.shoalwatch.ui.browse.Work
import app.shoalwatch.ui.browse.isWorking
import app.shoalwatch.ui.components.Artwork
import app.shoalwatch.ui.components.ArtworkAspect
import app.shoalwatch.ui.components.BannerActions
import app.shoalwatch.ui.components.CollectMessages
import app.shoalwatch.ui.components.EmptyState
import app.shoalwatch.ui.components.EpisodeRow
import app.shoalwatch.ui.components.ExpandableText
import app.shoalwatch.ui.components.FavouriteButton
import app.shoalwatch.ui.components.FreshnessBanner
import app.shoalwatch.ui.components.Gutter
import app.shoalwatch.ui.components.HeroBackdrop
import app.shoalwatch.ui.components.ItemActionsSheet
import app.shoalwatch.ui.components.OverlayBackButton
import app.shoalwatch.ui.components.ReadableBox
import app.shoalwatch.ui.components.RefreshLine
import app.shoalwatch.ui.components.SheetActions
import app.shoalwatch.ui.components.SheetTarget
import app.shoalwatch.ui.components.SignedOutNotice
import app.shoalwatch.ui.components.SkeletonBlock
import app.shoalwatch.ui.components.SkeletonEpisodes
import app.shoalwatch.ui.components.WatchedButton
import app.shoalwatch.ui.present.Destination
import app.shoalwatch.ui.present.Strings
import app.shoalwatch.ui.present.displayName
import app.shoalwatch.ui.present.episodeRow
import app.shoalwatch.ui.present.plainOverview
import app.shoalwatch.ui.present.rememberStrings
import app.shoalwatch.ui.present.seasonLabel
import app.shoalwatch.ui.present.seriesFacts
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
)

/**
 * A series: a wide picture and title at the top that scrolls away (a slim bar with the title and Back takes its place), the
 * overview, "Mark series as watched" and favourite, the seasons as chips, and the episodes of the chosen season. Content is
 * capped at a readable width and centred on a tablet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesScreen(state: SeriesUiState, actions: SeriesActions, messages: Flow<String>, snackbar: SnackbarHostState) {
    CollectMessages(messages, snackbar)
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (state.phase) {
                SeriesPhase.SIGNED_OUT -> Column(Modifier.statusBarsPadding()) {
                    OverlayBackButton(actions.onBack, Modifier.padding(8.dp))
                    SignedOutNotice(actions.onOpenServers)
                }
                SeriesPhase.GONE -> Column(Modifier.statusBarsPadding()) {
                    OverlayBackButton(actions.onBack, Modifier.padding(8.dp))
                    EmptyState(stringResource(R.string.gone_title), stringResource(R.string.gone_body), actionLabel = stringResource(R.string.cd_back), onAction = actions.onBack)
                }
                else -> Column(Modifier.fillMaxSize()) {
                    RefreshLine(state.refreshing && !state.pulling, Modifier.statusBarsPadding())
                    PullToRefreshBox(isRefreshing = state.pulling, onRefresh = actions.onRefresh, modifier = Modifier.fillMaxSize()) {
                        SeriesContent(state, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun SeriesContent(state: SeriesUiState, actions: SeriesActions) {
    val strings = rememberStrings()
    val session = state.session
    val listState = rememberLazyListState()
    var sheetId by rememberSaveable(session) { mutableStateOf<String?>(null) }
    val series = state.series
    val title = series?.item?.displayName(strings).orEmpty()
    val banner = BannerActions(actions.onRefresh, { session?.let { actions.onSignIn(it.serverId) } }, { session?.let { actions.onReview(it.serverId) } })
    val showBar by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
    val overview = series?.item?.overview?.let { plainOverview(it) }.orEmpty()
    val heroArtwork = if (series != null && session != null) series.item.backdropArtwork(session) ?: series.item.primaryArtwork(session) else null
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
                        HeroBackdrop(heroArtwork, title, actions.onBack)
                        Header(state, title, strings)
                    }
                }
                state.banner?.let { kind -> item(key = "banner") { FreshnessBanner(kind, banner) } }
                if (series != null) {
                    if (overview.isNotEmpty()) item(key = "overview") { ExpandableText(overview, Modifier.padding(horizontal = Gutter)) }
                    item(key = "actions") { SeriesActionsRow(state, actions) }
                }
                if (state.seasons.isNotEmpty()) {
                    item(key = "seasons") { SeasonChips(state, actions) }
                    item(key = "season-header") { SeasonHeader(state, actions) }
                }
                episodes(state, actions, session, strings) { sheetId = it }
            }
        }
        AnimatedVisibility(visible = showBar, modifier = Modifier.align(Alignment.TopStart)) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer).statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OverlayBackButton(actions.onBack)
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.semantics { heading() })
            }
        }
    }

    val target = sheetId?.let { id -> state.episodes.firstOrNull { it.item.id == id } }
    if (target != null) {
        ItemActionsSheet(
            SheetTarget(
                itemId = target.item.id, title = target.item.displayName(strings), played = target.userData?.played == true,
                favourite = target.userData?.favourite == true, canRemoveFromResume = false, seriesId = null,
            ),
            SheetActions({}, actions.onSetPlayed, actions.onSetFavourite, {}, { actions.onOpen(Destination.Detail(it)) }),
            onDismiss = { sheetId = null },
        )
    }
}

/** Items before the first episode in the list: hero, optional banner and overview, actions, chips, season header. Only used to scroll to an episode. */
private const val EPISODES_START = 3

@Composable
private fun Header(state: SeriesUiState, title: String, strings: Strings) {
    val series = state.series
    Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (series != null) {
            Artwork(state.session?.let { series.item.primaryArtwork(it) }, null, aspect = ArtworkAspect.POSTER, title = title, modifier = Modifier.width(96.dp))
        } else {
            SkeletonBlock(Modifier.width(96.dp), ratio = 2f / 3f)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (series != null) {
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                seriesFacts(strings, series.item, state.seasons.size)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                SkeletonBlock(Modifier.padding(top = 4.dp).width(180.dp).height(28.dp))
                SkeletonBlock(Modifier.width(120.dp).height(18.dp))
            }
        }
    }
}

@Composable
private fun SeriesActionsRow(state: SeriesUiState, actions: SeriesActions) {
    val series = state.series ?: return
    val id = series.item.id
    Row(Modifier.fillMaxWidth().padding(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        WatchedButton(
            stringResource(if (state.seriesPlayed) R.string.series_mark_unwatched else R.string.series_mark_watched),
            watched = state.seriesPlayed, working = state.working.contains(Work(id, ActionKind.PLAYED)),
            onClick = { actions.onSetSeriesPlayed(!state.seriesPlayed) }, modifier = Modifier.weight(1f),
        )
        FavouriteButton(state.seriesFavourite, state.working.contains(Work(id, ActionKind.FAVOURITE)), { actions.onSetSeriesFavourite(!state.seriesFavourite) })
    }
}

@Composable
private fun SeasonChips(state: SeriesUiState, actions: SeriesActions) {
    val strings = rememberStrings()
    val rowState = rememberLazyListState()
    val selected = state.seasons.indexOfFirst { it.item.id == state.selectedSeasonId }
    LaunchedEffect(selected) { if (selected >= 0) rowState.animateScrollToItem(selected) }
    LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.seasons, key = { it.item.id }) { season ->
            FilterChip(
                selected = season.item.id == state.selectedSeasonId,
                onClick = { actions.onPickSeason(season.item.id) },
                label = { Text(seasonLabel(strings, season.item)) },
            )
        }
    }
}

@Composable
private fun SeasonHeader(state: SeriesUiState, actions: SeriesActions) {
    val strings = rememberStrings()
    val season = state.selectedSeason ?: return
    var open by rememberSaveable { mutableStateOf(false) }
    val played = season.userData?.played == true
    Row(Modifier.fillMaxWidth().padding(start = Gutter, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            seasonLabel(strings, season.item), Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.titleLarge,
        )
        Box {
            IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.season_menu_cd)) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (played) R.string.season_mark_unwatched else R.string.season_mark_watched), style = MaterialTheme.typography.bodyLarge) },
                    onClick = { open = false; actions.onSetSeasonPlayed(season.item.id, !played) },
                )
            }
        }
    }
}

private fun LazyListScope.episodes(
    state: SeriesUiState,
    actions: SeriesActions,
    session: Session?,
    strings: Strings,
    onMenu: (String) -> Unit,
) {
    if (session == null) return
    if (state.episodes.isEmpty()) {
        item(key = "episodes-empty") {
            when {
                state.banner != null && state.series == null -> EmptyState(stringResource(R.string.error_title), stringResource(R.string.error_body))
                state.refreshing || state.selectedSeasonId == null && state.banner == null -> SkeletonEpisodes()
                state.selectedSeasonId != null -> Text(
                    stringResource(R.string.episodes_empty), Modifier.padding(horizontal = Gutter),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        return
    }
    items(state.episodes.distinctBy { it.item.id }, key = { it.item.id }) { entry: ItemWithUserData ->
        val row = remember(entry, session, strings) { episodeRow(entry, session, strings) }
        Box(Modifier.padding(horizontal = Gutter)) {
            EpisodeRow(
                row, onClick = { actions.onOpen(Destination.Detail(entry.item.id)) }, onMenu = { onMenu(entry.item.id) },
                highlighted = entry.item.id == state.highlightEpisodeId, working = state.working.isWorking(entry.item.id),
            )
        }
    }
}
