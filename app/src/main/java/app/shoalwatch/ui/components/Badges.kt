package app.shoalwatch.ui.components

import app.shoalwatch.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import app.shoalwatch.ui.theme.ShoalwatchTheme

/**
 * A thin bar showing how far through an item you are. It is decoration: the percentage is always also written down (in the
 * card's label), so the bar is never the only signal. [progress] is 0 to 1.
 */
@Composable
fun ProgressBarThin(progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.surfaceVariant).clearAndSetSemantics {}) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0f, 1f)).background(MaterialTheme.colorScheme.primary))
    }
}

/**
 * The "watched" tick: a filled circle with a check mark, so it is shape and symbol rather than colour. While a change is on its
 * way to the server ([working]) it turns into a small spinner, so a tap never looks missed. Decorative: the card's label says "watched".
 */
@Composable
fun WatchedBadge(modifier: Modifier = Modifier, working: Boolean = false) {
    Surface(modifier.size(26.dp).clearAndSetSemantics {}, shape = CircleShape, color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
        Box(contentAlignment = Alignment.Center) {
            if (working) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            else Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp))
        }
    }
}

/** A small spinner on a dark disc, for a card whose change is on its way to the server but that has no tick to turn into. */
@Composable
fun WorkingBadge(modifier: Modifier = Modifier) {
    Surface(modifier.size(26.dp).clearAndSetSemantics {}, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Box(contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) }
    }
}

/** How many episodes of a series are still unwatched. Decorative: the card's label says it in words. */
@Composable
fun UnwatchedBadge(count: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier.clearAndSetSemantics {},
        shape = RoundedCornerShape(13.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
    ) {
        Text(
            if (count > 99) stringResource(R.string.unwatched_many) else count.toString(),
            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(showBackground = true, name = "Badges")
@Composable
private fun BadgesPreview() = ShoalwatchTheme {
    Box(Modifier.padding(16.dp)) {
        androidx.compose.foundation.layout.Column(verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)) {
            WatchedBadge()
            WatchedBadge(working = true)
            UnwatchedBadge(7)
            ProgressBarThin(0.42f, Modifier.size(width = 160.dp, height = 4.dp))
        }
    }
}
