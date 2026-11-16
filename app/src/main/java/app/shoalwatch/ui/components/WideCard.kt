package app.shoalwatch.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
 * A landscape card for Continue Watching and Next Up: the picture with a thin progress bar along its foot, then the title
 * (the series, for an episode) and a line beneath ("S2 E5 · Episode title", or the time left). One labelled element; the
 * "more actions" button is separate.
 */
@Composable
fun WideCard(
    card: CardUi,
    onClick: () -> Unit,
    onMenu: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = PhoneWideCardWidth,
    working: Boolean = false,
) {
    Box(modifier.width(width)) {
        Column(
            Modifier.fillMaxWidth().clip(CardShape).cardAction(card.label, card.menuLabel, onClick, onMenu, if (working) stringResource(R.string.cd_working) else null),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // The bar is drawn inside the picture's rounded corners, along its foot.
            Box(Modifier.clip(ArtworkShape)) {
                Artwork(card.artwork, contentDescription = null, aspect = ArtworkAspect.WIDE, title = card.title, modifier = Modifier.fillMaxWidth())
                if (card.progress != null) ProgressBarThin(card.progress, Modifier.align(Alignment.BottomCenter))
                when {
                    working -> WorkingBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
                    card.watched -> WatchedBadge(Modifier.align(Alignment.TopStart).padding(8.dp))
                }
            }
            Column(Modifier.padding(horizontal = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(card.title, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = titleLines(1), overflow = TextOverflow.Ellipsis)
                if (card.subtitle != null) {
                    Text(card.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = titleLines(1), overflow = TextOverflow.Ellipsis)
                }
                if (card.percentText != null) {
                    Text(card.percentText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        ItemMenuButton(card.menuLabel, onMenu, Modifier.align(Alignment.TopEnd))
    }
}

@Preview(showBackground = true, name = "Wide cards")
@Composable
private fun WideCardPreview() = ShoalwatchTheme {
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        WideCard(sampleCard("Tidal", "S2 E5 · The Turning Tide", progress = 0.42f), {}, {})
        WideCard(sampleCard("The Example Film", "23 min left", progress = 0.7f), {}, {})
    }
}
