package app.shoalwatch.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import app.shoalwatch.core.AppError
import app.shoalwatch.data.playback.MediaSegment
import app.shoalwatch.data.playback.PlayMethod
import app.shoalwatch.data.playback.PlaybackState
import app.shoalwatch.data.playback.SegmentKind
import app.shoalwatch.data.playback.SubtitleAppearance
import app.shoalwatch.data.playback.SubtitleBackground
import app.shoalwatch.data.playback.SubtitleColour
import app.shoalwatch.data.playback.SubtitleEdge
import app.shoalwatch.data.playback.UpNextState
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.theme.ShoalwatchTheme

/* Previews of the player in its main states, on a black picture (no video in a preview). */

private val session = Session("0".repeat(31) + "a", "0".repeat(31) + "b")

private val playing = PlaybackState.EMPTY.copy(
    session = session, itemId = "0".repeat(31) + "c", title = "The Turning Tide", episodeLine = "Tidal", phase = PlaybackState.Phase.PLAYING,
    positionMs = 750_000, durationMs = 2_700_000, method = PlayMethod.TRANSCODE, next = "0".repeat(31) + "d",
)

private val look = SubtitleAppearance(100, SubtitleColour.WHITE, SubtitleBackground.SHADED, SubtitleEdge.OUTLINE, true, 8)

private val actions = PlayerActions(
    {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
)

@Composable
private fun Screen(state: PlayerUiState) = ShoalwatchTheme { PlayerScreen(state, actions) }

@Preview(widthDp = 640, heightDp = 360, name = "Player: controls") @Composable
private fun ControlsPreview() = Screen(PlayerUiState(session, playing, appearance = look, streamInfoShown = true))

@Preview(widthDp = 640, heightDp = 360, name = "Player: up next") @Composable
private fun UpNextPreview() = Screen(PlayerUiState(session, playing.copy(upNext = UpNextState("x", "The Open Sea", 8, false)), controlsVisible = false, appearance = look))

@Preview(widthDp = 640, heightDp = 360, name = "Player: skip intro") @Composable
private fun SkipPreview() = Screen(PlayerUiState(session, playing.copy(segmentNow = MediaSegment(SegmentKind.INTRO, 0, 60_000)), controlsVisible = false, appearance = look))

@Preview(widthDp = 640, heightDp = 360, name = "Player: locked") @Composable
private fun LockedPreview() = Screen(PlayerUiState(session, playing, locked = true, appearance = look))

@Preview(widthDp = 640, heightDp = 360, name = "Player: offline") @Composable
private fun FailedPreview() = Screen(PlayerUiState(session, playing.copy(phase = PlaybackState.Phase.FAILED, error = AppError.Kind.OFFLINE), appearance = look))
