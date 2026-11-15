package app.shoalwatch.ui.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.downloads.OfflineSeason
import app.shoalwatch.ui.components.Artwork
import app.shoalwatch.ui.components.ArtworkAspect
import app.shoalwatch.ui.components.EmptyState
import app.shoalwatch.ui.components.SectionHeader
import app.shoalwatch.ui.present.Strings
import app.shoalwatch.ui.present.displayName
import app.shoalwatch.ui.present.plainOverview
import app.shoalwatch.ui.present.rememberStrings
import app.shoalwatch.ui.present.wideArtwork

/** What the offline series screen can do. */
data class OfflineSeriesActions(val onBack: () -> Unit, val onOpenItem: (String) -> Unit, val onPlay: (String) -> Unit)

/**
 * One downloaded series, offline: its header (artwork, overview), then each season as a heading ("Season 1", "Specials" last)
 * with its downloaded episodes in order. A row opens the detail screen (Room, so it works offline); Play opens the player,
 * which picks the saved copy.
 */
@Composable
fun OfflineSeriesScreen(state: OfflineSeriesUiState, actions: OfflineSeriesActions) {
    val strings = rememberStrings()
    val series = state.series
    val session = state.session
    val title = series?.series?.item?.displayName(strings).orEmpty()
    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.onBack, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
            }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).semantics { heading() })
        }
        when {
            session == null || state.loading -> Unit
            state.gone || series == null -> LazyColumn(Modifier.fillMaxSize()) {
                item { EmptyState(stringResource(R.string.gone_title), stringResource(R.string.gone_body)) }
            }
            else -> LazyColumn(Modifier.fillMaxSize()) {
                series.series?.let { header ->
                    item {
                        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Artwork(wideArtwork(header.item, session), null, aspect = ArtworkAspect.WIDE, title = title, modifier = Modifier.fillMaxWidth())
                            val overview = plainOverview(header.item.overview)
                            if (overview.isNotEmpty()) Text(overview, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                for (season in ordered(series.seasons)) {
                    item { SectionHeader(seasonHeading(strings, season), Modifier.padding(vertical = 8.dp)) }
                    items(season.episodes, key = { it.download.itemId }) { OfflineEpisodeRow(it, session, actions.onOpenItem, actions.onPlay) }
                }
                item { Spacer(Modifier.heightIn(min = 24.dp)) }
            }
        }
    }
}

/** Numbered seasons in order, then seasons with no number, then Specials (season 0), then episodes with no season at all. */
internal fun ordered(seasons: List<OfflineSeason>): List<OfflineSeason> = seasons.sortedWith(
    compareBy<OfflineSeason>({
        val n = it.seasonNumber ?: it.season?.item?.indexNumber
        when {
            it.season == null && n == null -> 3
            n == 0 -> 2
            n == null -> 1
            else -> 0
        }
    }, { it.seasonNumber ?: it.season?.item?.indexNumber ?: 0 }),
)

internal fun seasonHeading(strings: Strings, s: OfflineSeason): String {
    val n = s.seasonNumber ?: s.season?.item?.indexNumber
    return when {
        n == 0 -> strings.get(R.string.season_specials)
        n != null -> strings.get(R.string.season_number, n.toString())
        else -> s.season?.item?.name?.ifBlank { null } ?: strings.get(R.string.season_unnamed)
    }
}
