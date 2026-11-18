package app.shoalwatch.ui.player

import app.shoalwatch.data.playback.PlaybackState
import app.shoalwatch.data.playback.Quality
import app.shoalwatch.data.playback.SleepChoice
import app.shoalwatch.data.playback.SubtitleAppearance
import app.shoalwatch.data.servers.Session
import app.shoalwatch.security.PinCheck

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
    /** "You've finished this episode." with watch-and-delete's buttons (or ALWAYS's line); null when not offered. */
    val watched: WatchedPromptUi? = null,
    /** "Watched together" is asking a protected sharer's PIN (security review M8 F12); the PIN itself is never here. */
    val sharerPin: SharerPinAsk? = null,
    /** What a watch-and-delete press did: said once in the snackbar, until dismissed. */
    val watchedMessage: WatchedMessage? = null,
    val leave: Boolean = false,
) {
    override fun toString() = "PlayerUiState(phase=${playback.phase}, leave=$leave)"
}

/**
 * The watch-and-delete prompt for one item, as its own [session] (it acts only as that session). [together]: how many other
 * signed-in profiles share the download (a count; never names). [always]: the profile's setting deletes it at the stop, so
 * only the information line shows.
 */
data class WatchedPromptUi(val session: Session, val itemId: String, val film: Boolean, val together: Int, val always: Boolean) {
    override fun toString() = "WatchedPromptUi(<redacted>)"
}

/** LEFT_OUT: "Watched together" marked the others, but a protected profile's PIN was not given, so it was left out (security review M8 F12). */
enum class WatchedMessage { DELETED, KEPT_FOR_OTHERS, KEPT_UNTIL_EVERYONE, FAILED, LEFT_OUT }

/**
 * "Watched together" asking a protected sharer's own PIN: its [name] (null when it could not be read: neutral words), the last
 * [answer] (Wrong / Wait), [busy] while checking, [failed] when the check itself could not run (review M-3).
 */
data class SharerPinAsk(val name: String?, val answer: PinCheck? = null, val busy: Boolean = false, val failed: Boolean = false) {
    override fun toString() = "SharerPinAsk(<redacted>)"
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
    val onWatchedDelete: () -> Unit = {},
    val onWatchedTogether: () -> Unit = {},
    val onKeepDownload: () -> Unit = {},
    val onDismissWatchedMessage: () -> Unit = {},
    val onSubmitSharerPin: () -> Unit = {},
    val onCancelSharerPin: () -> Unit = {},
    /** The top bar's "Picture-in-picture" button; null where the window cannot do it (no button then). */
    val onEnterPip: (() -> Unit)? = null,
)
