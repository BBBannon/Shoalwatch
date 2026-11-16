package app.shoalwatch.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shoalwatch.R

/**
 * "You've finished this episode." with "Watched — delete download", "Watched together" (only when other signed-in profiles share
 * it; a count, never names) and "Keep download"; with ALWAYS only the information line. A card beside Up next: it never blocks
 * playback or the countdown, and ignoring it leaves the download. Its text is a polite live region, announced once with the
 * first button's name. The content scrolls if the space is short, so the card stays on screen.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WatchedPrompt(prompt: WatchedPromptUi, actions: PlayerActions, modifier: Modifier = Modifier) {
    val title = stringResource(if (prompt.film) R.string.watched_film else R.string.watched_episode)
    val always = stringResource(R.string.watched_always)
    val spoken = if (prompt.always) "$title $always" else "$title ${stringResource(R.string.watched_delete_button_spoken)}"
    Card(modifier.widthIn(max = 360.dp).testTag(TAG), colors = CardDefaults.cardColors(containerColor = Color(0xE6202020), contentColor = OnVideo)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                title, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { contentDescription = spoken; liveRegion = LiveRegionMode.Polite },
            )
            if (prompt.always) {
                Text(always, style = MaterialTheme.typography.bodyLarge)
                return@Column
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.onWatchedDelete, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.watched_delete)) }
                if (prompt.together > 0) {
                    val label = stringResource(R.string.watched_together_label)
                    OutlinedButton(onClick = actions.onWatchedTogether, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = label }) {
                        Text(stringResource(R.string.watched_together), color = OnVideo)
                    }
                }
                OutlinedButton(onClick = actions.onKeepDownload, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.watched_keep), color = OnVideo)
                }
            }
        }
    }
}

/** The prompt card's test tag. */
internal const val WATCHED_PROMPT_TAG = "watched_prompt"
private const val TAG = WATCHED_PROMPT_TAG

/** What a watch-and-delete press did, for the snackbar. */
@androidx.annotation.StringRes
internal fun watchedMessageText(m: WatchedMessage): Int = when (m) {
    WatchedMessage.DELETED -> R.string.watched_deleted
    WatchedMessage.KEPT_FOR_OTHERS -> R.string.watched_kept_for_others
    WatchedMessage.KEPT_UNTIL_EVERYONE -> R.string.watched_kept_until_everyone
    WatchedMessage.FAILED -> R.string.watched_failed
}
