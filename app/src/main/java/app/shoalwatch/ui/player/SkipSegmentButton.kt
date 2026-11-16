package app.shoalwatch.ui.player

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.shoalwatch.data.playback.MediaSegment
import app.shoalwatch.ui.present.PlayerText

/**
 * "Skip intro", "Skip credits", ... while the position is inside that segment; 56dp high. It takes input focus once when it
 * appears (for switch and keyboard users); it does not move TalkBack's focus, so a screen-reader user is not pulled away.
 */
@Composable
internal fun SkipSegmentButton(segment: MediaSegment, onSkip: () -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(segment.kind, segment.startMs) { runCatching { focus.requestFocus() } }
    Button(
        onClick = onSkip,
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xE6202020), contentColor = OnVideo),
        modifier = modifier.heightIn(min = 56.dp).focusRequester(focus),
    ) { Text(stringResource(PlayerText.skipLabel(segment.kind)), style = MaterialTheme.typography.titleMedium) }
}
