package app.shoalwatch.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.playback.PlaybackState
import app.shoalwatch.data.playback.PlaybackState.Phase
import app.shoalwatch.ui.present.PlayerText
import app.shoalwatch.ui.present.rememberStrings

/** White on the darkened picture, whatever the app's theme. */
internal val OnVideo = Color.White
internal val Scrim = Color(0x99000000)
private val Target = 48.dp

/**
 * The controls over the picture: Back, the title and the top-row buttons (wrapping into more rows at large text, and scrolling
 * if even that does not fit), the centre row (previous, back 10 s, play/pause, forward 10 s, next), and the seek bar with the
 * elapsed and remaining time. Locked, only the lock button and its hint are shown.
 */
@Composable
internal fun PlayerControls(state: PlayerUiState, actions: PlayerActions, streamLine: String?) {
    if (state.locked) return LockedControls(actions)
    val p = state.playback
    Column(Modifier.fillMaxSize().background(Scrim)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(8.dp)) { TopArea(state, actions, streamLine) }
        }
        CentreRow(p, actions)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) { SeekArea(p, actions) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TopArea(state: PlayerUiState, actions: PlayerActions, streamLine: String?) {
    val p = state.playback
    Row(verticalAlignment = Alignment.Top) {
        ControlButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_back), actions.onBack)
        Column(Modifier.weight(1f).padding(start = 8.dp, top = 4.dp)) {
            p.title?.let { Text(it, style = MaterialTheme.typography.titleLarge, color = OnVideo) }
            p.episodeLine?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = OnVideo) }
            if (state.streamInfoShown && streamLine != null) Text(streamLine, style = MaterialTheme.typography.bodyMedium, color = OnVideo)
        }
    }
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        ControlButton(PlayerIcons.Subtitles, stringResource(R.string.player_tracks), onClick = { actions.onOpenSheet(PlayerSheet.TRACKS) })
        ControlButton(PlayerIcons.Quality, stringResource(R.string.player_quality), onClick = { actions.onOpenSheet(PlayerSheet.QUALITY) })
        ControlButton(PlayerIcons.Speed, stringResource(R.string.player_speed), onClick = { actions.onOpenSheet(PlayerSheet.SPEED) })
        ControlButton(PlayerIcons.Sleep, stringResource(R.string.player_sleep), onClick = { actions.onOpenSheet(PlayerSheet.SLEEP) })
        ControlButton(PlayerIcons.TextStyle, stringResource(R.string.player_subtitle_style), onClick = { actions.onOpenSheet(PlayerSheet.SUBTITLE_STYLE) })
        ControlButton(Icons.Filled.Info, stringResource(R.string.player_stream_info), onClick = { actions.onOpenSheet(PlayerSheet.STREAM_INFO) })
        ControlButton(Icons.Filled.Lock, stringResource(R.string.player_lock), actions.onToggleLock)
    }
}

@Composable
private fun CentreRow(p: PlaybackState, actions: PlayerActions) {
    val episode = p.episodeLine != null || p.previous != null || p.next != null
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        if (episode) {
            ControlButton(PlayerIcons.Previous, stringResource(R.string.player_previous), actions.onPrevious, enabled = p.previous != null, reason = stringResource(R.string.player_no_previous))
        }
        ControlButton(PlayerIcons.Rewind, stringResource(R.string.player_back_10), actions.onSkipBack)
        val playing = p.phase == Phase.PLAYING || p.phase == Phase.BUFFERING
        ControlButton(
            if (playing) PlayerIcons.Pause else Icons.Filled.PlayArrow, stringResource(if (playing) R.string.player_pause else R.string.player_play),
            actions.onPlayPause, size = 64.dp,
        )
        ControlButton(PlayerIcons.Forward, stringResource(R.string.player_forward_10), actions.onSkipForward)
        if (episode) {
            ControlButton(PlayerIcons.Next, stringResource(R.string.player_next), actions.onNext, enabled = p.next != null, reason = stringResource(R.string.player_no_next))
        }
    }
}

/** The seek bar: "Position", its state in words ("12:30 of 45:00"), a SetProgress action; it seeks when let go. */
@Composable
private fun SeekArea(p: PlaybackState, actions: PlayerActions) {
    val strings = rememberStrings()
    val duration = p.durationMs?.takeIf { it > 0 }
    var dragging by remember { mutableStateOf<Float?>(null) }
    val shownMs = dragging?.toLong() ?: p.positionMs
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        val label = stringResource(R.string.player_seek)
        Slider(
            value = (dragging ?: p.positionMs.toFloat()).coerceIn(0f, (duration ?: 1L).toFloat()),
            onValueChange = { dragging = it },
            onValueChangeFinished = { dragging?.let { actions.onSeek(it.toLong()) }; dragging = null },
            valueRange = 0f..(duration ?: 1L).toFloat(),
            enabled = duration != null,
            colors = SliderDefaults.colors(thumbColor = OnVideo, activeTrackColor = OnVideo),
            modifier = Modifier.fillMaxWidth().heightIn(min = Target).semantics {
                contentDescription = label
                if (duration != null) stateDescription = PlayerText.seekState(strings, shownMs, duration)
            },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(PlayerText.clock(shownMs), style = MaterialTheme.typography.bodyMedium, color = OnVideo)
            if (duration != null) {
                Text(stringResource(R.string.player_remaining, PlayerText.clock(duration - shownMs)), style = MaterialTheme.typography.bodyMedium, color = OnVideo)
            }
        }
    }
}

/** Only the lock (as "Unlock screen") and the hint; everything else is out of reach while locked. */
@Composable
private fun LockedControls(actions: PlayerActions) {
    Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.End) {
        ControlButton(PlayerIcons.LockOpen, stringResource(R.string.player_unlock), actions.onToggleLock)
        Text(
            stringResource(R.string.player_locked_hint), style = MaterialTheme.typography.bodyLarge, color = OnVideo,
            modifier = Modifier.background(Scrim).padding(8.dp),
        )
    }
}

/**
 * An icon button on the picture with its TalkBack label, at least 48dp. Disabled with a [reason], it stays focusable and says
 * why ("This is the last episode") instead of disappearing.
 */
@Composable
internal fun ControlButton(icon: ImageVector, label: String, onClick: () -> Unit, enabled: Boolean = true, reason: String? = null, size: Dp = Target) {
    IconButton(
        onClick = onClick, enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = OnVideo, disabledContentColor = OnVideo.copy(alpha = 0.38f)),
        modifier = Modifier.size(size).semantics {
            contentDescription = label
            if (!enabled && reason != null) stateDescription = reason
        },
    ) { Icon(icon, contentDescription = null, modifier = Modifier.size(if (size > Target) 40.dp else 28.dp)) }
}
