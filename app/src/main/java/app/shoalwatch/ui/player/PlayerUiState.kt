package app.shoalwatch.ui.player

import app.shoalwatch.data.playback.PlaybackState
import app.shoalwatch.data.playback.Quality
import app.shoalwatch.data.playback.SleepChoice
import app.shoalwatch.data.playback.SubtitleAppearance
import app.shoalwatch.data.servers.Session

/**
 * What the player screen shows. [playback] is the controller's state (no URL, token or Media3 type; it holds a title and a
 * position, so this is never printed). [session] is the owner, the first active session the screen saw. [leave]: the session
 * ended or the controller closed, so the route pops at once.
 */
data class PlayerUiState(
    val session: Session? = null,
    val playback: PlaybackState = PlaybackState.EMPTY,
    val controlsVisible: Boolean = true,
    /** Touches are locked (and the orientation pinned) except the lock button itself. */
    val locked: Boolean = false,
    val sheet: PlayerSheet? = null,
    val appearance: SubtitleAppearance? = null,
    /** TalkBack's touch exploration is on: the controls never hide on their own. */
    val touchExploration: Boolean = false,
    /** The plain-words line about how the video is played, shown under the title for 4 s at the start. */
    val streamInfoShown: Boolean = false,
    /** The sleep timer paused playback: said once, until dismissed. */
    val sleepFired: Boolean = false,
    val leave: Boolean = false,
) {
    override fun toString() = "PlayerUiState(phase=${playback.phase}, leave=$leave)"
}

enum class PlayerSheet { TRACKS, QUALITY, SPEED, SLEEP, SUBTITLE_STYLE, STREAM_INFO }

/** What the player screen can ask for. The route wires these to the ViewModel and to navigation. */
class PlayerActions(
    val onBack: () -> Unit,
    val onTapSurface: () -> Unit,
    val onPlayPause: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onSkipBack: () -> Unit,
    val onSkipForward: () -> Unit,
    /** 10 s back or forward at once; the screen adds repeated taps up in its bubble ("−30 s"). */
    val onDoubleTap: (forward: Boolean) -> Unit,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
    val onSkipSegment: () -> Unit,
    val onPlayUpNext: () -> Unit,
    val onCancelUpNext: () -> Unit,
    val onToggleLock: () -> Unit,
    val onOpenSheet: (PlayerSheet) -> Unit,
    val onCloseSheet: () -> Unit,
    val onSelectAudio: (Int) -> Unit,
    val onSelectSubtitle: (Int?) -> Unit,
    val onBurnIn: (Int) -> Unit,
    val onQuality: (Quality) -> Unit,
    val onSpeed: (Float) -> Unit,
    val onSleep: (SleepChoice) -> Unit,
    val onAppearance: (SubtitleAppearance) -> Unit,
    val onDelay: (Int) -> Unit,
    val onRetry: () -> Unit,
    val onSignIn: () -> Unit,
    val onDismissSubtitleNotice: () -> Unit,
    val onDismissSleepFired: () -> Unit,
)
