package app.shoalwatch.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.shoalwatch.R
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.shoalwatch.ui.present.CardUi
import app.shoalwatch.ui.theme.ShoalwatchTheme

/**
 * A film or series poster with its title (up to two lines, more at large text) and year beneath. A watched tick or an unwatched
 * count sits on the corner; [working] turns the tick into a spinner while a change reaches the server. One focusable element
 * labelled with the whole card; the "more actions" button is separate. Give it a [width] (or fill the grid cell with null).
 */
@Composable
fun PosterCard(
    card: CardUi,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = null,
    working: Boolean = false,
) {
    Box(if (width != null) modifier.width(width) else modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().clip(CardShape).cardAction(card.label, card.menuLabel, onClick, onMenu, if (working) stringResource(R.string.cd_working) else null),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box {
                Artwork(card.artwork, contentDescription = null, aspect = ArtworkAspect.POSTER, title = card.title, modifier = Modifier.fillMaxWidth())
                when {
                    working -> WorkingBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
                    card.watched -> WatchedBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
                    card.unwatchedCount != null -> UnwatchedBadge(card.unwatchedCount, Modifier.align(Alignment.TopStart).padding(8.dp))
                }
            }
            Text(card.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = titleLines(), overflow = TextOverflow.Ellipsis)
            if (card.subtitle != null) {
                Text(card.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        ItemMenuButton(card.menuLabel, onMenu, Modifier.align(Alignment.TopEnd))
    }
}


@Preview(showBackground = true, name = "Poster cards")
@Composable
private fun PosterCardPreview() = ShoalwatchTheme {
    androidx.compose.foundation.layout.Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        PosterCard(sampleCard("The Example Film", "2019", watched = true), {}, {}, width = 128.dp)
        PosterCard(sampleCard("A Much Longer Title That Wraps", "2021", unwatched = 7), {}, {}, width = 128.dp)
    }
}
