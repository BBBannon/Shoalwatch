package app.shoalwatch.ui.series

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.images.primaryArtwork
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.browse.ActionKind
import app.shoalwatch.ui.browse.Work
import app.shoalwatch.ui.browse.isWorking
import app.shoalwatch.ui.components.Artwork
import app.shoalwatch.ui.components.ArtworkAspect
import app.shoalwatch.ui.components.BannerActions
import app.shoalwatch.ui.components.EpisodeRow
import app.shoalwatch.ui.components.ErrorState
import app.shoalwatch.ui.components.FavouriteButton
import app.shoalwatch.ui.components.Gutter
import app.shoalwatch.ui.components.ItemActionsSheet
import app.shoalwatch.ui.components.LocalCovered
import app.shoalwatch.ui.components.SheetActions
import app.shoalwatch.ui.components.SheetTarget
import app.shoalwatch.ui.components.SkeletonBlock
import app.shoalwatch.ui.components.SkeletonEpisodes
import app.shoalwatch.ui.components.WatchedButton
import app.shoalwatch.ui.components.refreshAction
import app.shoalwatch.ui.downloads.DownloadControls
import app.shoalwatch.ui.downloads.EpisodeDownloadControl
import app.shoalwatch.ui.downloads.SeriesDownloadMenu
import app.shoalwatch.ui.present.Destination
import app.shoalwatch.ui.present.Strings
import app.shoalwatch.ui.present.episodeRow
import app.shoalwatch.ui.present.rememberStrings
import app.shoalwatch.ui.present.seasonLabel
import app.shoalwatch.ui.present.seriesFacts
import app.shoalwatch.ui.present.sheetTitle

/* The series page's blocks, shared by the one-column page (`SeriesScreen`) and the two panes (`SeriesTwoPane`, Task 10). */

@Composable
internal fun Header(state: SeriesUiState, title: String, strings: Strings, onRefresh: () -> Unit) {
    val series = state.series
    Row(Modifier.fillMaxWidth().padding(horizontal = Gutter, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (series != null) {
            Artwork(state.session?.let { series.item.primaryArtwork(it) }, null, aspect = ArtworkAspect.POSTER, title = title, modifier = Modifier.width(96.dp))
        } else {
            val loading = stringResource(R.string.cd_loading_content)
            SkeletonBlock(Modifier.width(96.dp).semantics { contentDescription = loading }, ratio = 2f / 3f)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (series != null) {
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.refreshAction(onRefresh).semantics { heading() })
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
internal fun SeriesActionsRow(state: SeriesUiState, actions: SeriesActions, download: DownloadControls?) {
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
    if (download != null) {
        val season = state.selectedSeason
        Box(Modifier.padding(horizontal = Gutter)) { SeriesDownloadMenu(season?.item?.id, season?.item?.let { it.indexNumber?.toString().orEmpty() }, download) }
    }
}

/** [onPicked]: told when the user taps a chip, before the pick goes to the ViewModel (the two-pane page resets its episodes). */
@Composable
internal fun SeasonChips(state: SeriesUiState, actions: SeriesActions, onPicked: () -> Unit = {}) {
    val strings = rememberStrings()
    val rowState = rememberLazyListState()
    val selected = state.seasons.indexOfFirst { it.item.id == state.selectedSeasonId }
    LaunchedEffect(selected) { if (selected >= 0) rowState.animateScrollToItem(selected) }
    LazyRow(state = rowState, contentPadding = PaddingValues(horizontal = Gutter), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.seasons, key = { it.item.id }) { season ->
            FilterChip(
                selected = season.item.id == state.selectedSeasonId,
                onClick = { onPicked(); actions.onPickSeason(season.item.id) },
                label = { Text(seasonLabel(strings, season.item)) },
            )
        }
    }
}

@Composable
internal fun SeasonHeader(state: SeriesUiState, actions: SeriesActions) {
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
            if (!LocalCovered.current) DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (played) R.string.season_mark_unwatched else R.string.season_mark_watched), style = MaterialTheme.typography.bodyLarge) },
                    onClick = { open = false; actions.onSetSeasonPlayed(season.item.id, !played) },
                )
            }
        }
    }
}

internal fun LazyListScope.episodes(
    state: SeriesUiState,
    actions: SeriesActions,
    session: Session?,
    strings: Strings,
    banner: BannerActions,
    download: DownloadControls?,
    onMenu: (String) -> Unit,
) {
    if (session == null) return
    if (state.episodes.isEmpty()) {
        item(key = "episodes-empty") {
            when {
                state.banner != null && state.series == null -> ErrorState(state.banner, banner)
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
        Row(Modifier.padding(horizontal = Gutter), verticalAlignment = Alignment.CenterVertically) {
            EpisodeRow(
                row, onClick = { actions.onOpen(Destination.Detail(entry.item.id)) }, onMenu = { onMenu(entry.item.id) },
                highlighted = entry.item.id == state.highlightEpisodeId, working = state.working.isWorking(entry.item.id), modifier = Modifier.weight(1f),
            )
            if (download != null) EpisodeDownloadControl(entry.item, download)
        }
    }
}

/** The More sheet for the episode [sheetId] names (nothing when it is null or no longer listed). */
@Composable
internal fun EpisodeSheet(state: SeriesUiState, actions: SeriesActions, sheetId: String?, onDismiss: () -> Unit) {
    val strings = rememberStrings()
    val target = sheetId?.let { id -> state.episodes.firstOrNull { it.item.id == id } } ?: return
    ItemActionsSheet(
        SheetTarget(
            itemId = target.item.id, title = target.item.sheetTitle(strings), played = target.userData?.played == true,
            favourite = target.userData?.favourite == true, canRemoveFromResume = false, seriesId = null,
        ),
        SheetActions({}, actions.onSetPlayed, actions.onSetFavourite, {}, { actions.onOpen(Destination.Detail(it)) }),
        onDismiss = onDismiss,
    )
}
