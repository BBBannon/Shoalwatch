package app.shoalwatch.ui.components

import app.shoalwatch.ui.present.rememberStrings
import app.shoalwatch.ui.present.joinAll
import app.shoalwatch.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.shoalwatch.ui.present.EpisodeUi
import app.shoalwatch.ui.theme.ShoalwatchTheme

/**
 * One episode in a season: thumbnail, "E5 · Title", runtime, a two-line overview and the watched tick or a progress bar.
 * [highlighted] picks out the episode the user came from. The row is one labelled element; the "more actions" button beside
 * it is its own 48dp control.
 */
@Composable
fun EpisodeRow(
    episode: EpisodeUi,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false,
    working: Boolean = false,
    select: CardSelect? = null,
) {
    val wide = isWideScreen()
    val thumbWidth = if (wide) 176.dp else 120.dp
    val spoken = listOfNotNull(
        if (working) stringResource(R.string.cd_working) else null,
        if (highlighted) stringResource(R.string.state_you_were_watching) else null,
    ).joinToString(", ").ifEmpty { null }
    Surface(
        modifier.fillMaxWidth(),
        shape = CardShape,
        color = if (highlighted || select?.selected == true) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        border = when {
            select?.selected == true -> BorderStroke(3.dp, MaterialTheme.colorScheme.primary)
            highlighted -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            else -> null
        },
    ) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Top) {
            Row(
                Modifier.weight(1f).clip(CardShape).cardAction(episode.label, episode.menuLabel, onClick, onMenu, spoken, select),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // The picture's box has a fixed width of its own: the progress bar fills that width and nothing more. Left to fill
                // whatever the row offers, it took the whole row and squeezed the text to nothing.
                Box(Modifier.width(thumbWidth).clip(ArtworkShape)) {
                    Artwork(episode.artwork, null, aspect = ArtworkAspect.WIDE, title = episode.title, modifier = Modifier.fillMaxWidth())
                    if (episode.progress != null) ProgressBarThin(episode.progress, Modifier.align(Alignment.BottomCenter))
                    if (select?.selected == true) SelectedMark(Modifier.align(Alignment.TopStart).padding(4.dp))
                }
                Column(Modifier.weight(1f).padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(episode.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = titleLines(2), overflow = TextOverflow.Ellipsis)
                    val meta = joinAll(rememberStrings(), listOfNotNull(episode.runtime, episode.percentText))
                    if (meta != null) Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (episode.overview.isNotEmpty()) {
                        Text(episode.overview, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                MenuDots(episode.menuLabel, onMenu)
                when {
                    working -> WorkingBadge()
                    episode.watched -> WatchedBadge()
                }
            }
        }
    }
}

@Preview(showBackground = true, name = "Episode rows")
@Composable
private fun EpisodeRowPreview() = ShoalwatchTheme {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        EpisodeRow(sampleEpisode("E1 · The Beginning", watched = true), {}, {})
        EpisodeRow(sampleEpisode("E2 · The Turning Tide", progress = 0.4f), {}, {}, highlighted = true)
    }
}
