package app.shoalwatch.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.playback.UpNextState

/**
 * "Up next: <title>" with "Playing in 8 seconds", "Play now" and "Cancel"; without auto-play (or once cancelled) "Play next
 * episode" and "Close". The countdown is announced when the card appears and again at 3 seconds, not every second.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun UpNextCard(upNext: UpNextState, actions: PlayerActions, modifier: Modifier = Modifier) {
    val seconds = upNext.secondsLeft
    // What a screen reader is told: the first count, then the count at 3 s (a change to this text is what gets announced).
    val first = remember(upNext.itemId) { seconds }
    val announced = if (seconds != null && seconds <= ANNOUNCE_AGAIN_AT && (first ?: 0) > ANNOUNCE_AGAIN_AT) ANNOUNCE_AGAIN_AT else first
    Card(modifier.widthIn(max = 360.dp), colors = CardDefaults.cardColors(containerColor = Color(0xE6202020), contentColor = OnVideo)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val title = upNext.title?.let { stringResource(R.string.up_next, it) } ?: stringResource(R.string.up_next_untitled)
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (seconds != null) {
                val spoken = announced?.let { pluralStringResource(R.plurals.up_next_in, it, it) }.orEmpty()
                Text(
                    pluralStringResource(R.plurals.up_next_in, seconds, seconds), style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.clearAndSetSemantics { contentDescription = spoken; liveRegion = LiveRegionMode.Polite },
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.onPlayUpNext, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (seconds != null) R.string.up_next_play else R.string.up_next_play_manual))
                }
                OutlinedButton(onClick = actions.onCancelUpNext, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(if (seconds != null) R.string.action_cancel else R.string.up_next_close), color = OnVideo)
                }
            }
        }
    }
}

private const val ANNOUNCE_AGAIN_AT = 3
