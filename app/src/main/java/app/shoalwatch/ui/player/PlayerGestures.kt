package app.shoalwatch.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.ui.present.PlayerText
import kotlinx.coroutines.delay

/**
 * The touch layer over the picture. A tap shows or hides the controls; a double tap on the left or right third skips 10 s back
 * or forward (taps in a row add up in a bubble: "−30 s"); a horizontal drag scrubs, showing the time it will go to, and seeks
 * when let go. No brightness or volume swipes. Every gesture has a button: the controls (which never hide under TalkBack).
 * Locked, only the tap works (it shows the lock button).
 */
@Composable
internal fun PlayerGestures(state: PlayerUiState, actions: PlayerActions) {
    val p = state.playback
    val locked = state.locked
    var skipped by remember { mutableIntStateOf(0) }
    var scrubTo by remember { mutableStateOf<Long?>(null) }
    val position by rememberUpdatedState(p.positionMs)
    val duration by rememberUpdatedState(p.durationMs)
    val current by rememberUpdatedState(actions)
    LaunchedEffect(skipped) { if (skipped != 0) { delay(BUBBLE_MS); skipped = 0 } }
    Box(
        Modifier.fillMaxSize()
            .pointerInput(locked) {
                detectTapGestures(
                    onTap = { current.onTapSurface() },
                    onDoubleTap = if (locked) null else { offset ->
                        val third = size.width / 3f
                        when {
                            offset.x < third -> { current.onDoubleTap(false); skipped = if (skipped < 0) skipped - 10 else -10 }
                            offset.x > third * 2 -> { current.onDoubleTap(true); skipped = if (skipped > 0) skipped + 10 else 10 }
                            else -> current.onTapSurface()
                        }
                    },
                )
            }
            .then(
                if (locked) Modifier else Modifier.pointerInput(Unit) {
                    var start = 0L
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { start = position; dx = 0f },
                        onDragEnd = { scrubTo?.let(current.onSeek); scrubTo = null },
                        onDragCancel = { scrubTo = null },
                    ) { _, amount ->
                        val d = duration ?: return@detectHorizontalDragGestures
                        dx += amount
                        // The whole width is two minutes either way, or the whole video if shorter.
                        val span = minOf(d, SCRUB_SPAN_MS)
                        scrubTo = (start + (dx / size.width * span).toLong()).coerceIn(0, d)
                    }
                },
            ),
    ) {
        val bubble = when {
            scrubTo != null -> stringResource(R.string.player_scrub_to, PlayerText.clock(scrubTo ?: 0))
            skipped < 0 -> stringResource(R.string.player_skip_amount_back, -skipped)
            skipped > 0 -> stringResource(R.string.player_skip_amount_forward, skipped)
            else -> null
        }
        if (bubble != null) {
            Text(
                bubble, style = MaterialTheme.typography.titleLarge, color = OnVideo,
                modifier = Modifier.align(Alignment.Center).background(Scrim, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

private const val BUBBLE_MS = 800L
private const val SCRUB_SPAN_MS = 120_000L
