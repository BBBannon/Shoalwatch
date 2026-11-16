package app.shoalwatch.ui.player

import app.shoalwatch.data.playback.PlayMethod
import app.shoalwatch.data.playback.PlaybackState
import app.shoalwatch.data.playback.PlaybackState.Phase
import app.shoalwatch.data.playback.SubtitleAppearance
import app.shoalwatch.data.playback.SubtitleBackground
import app.shoalwatch.data.playback.SubtitleColour
import app.shoalwatch.data.playback.SubtitleEdge
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.id

/** An episode playing at 12:30 of 45:00, with a next episode and no previous one, controls up. */
internal val playingEpisode = PlaybackState.EMPTY.copy(
    session = BEN, itemId = id(101), title = "The Tide", episodeLine = "Series 50", phase = Phase.PLAYING,
    positionMs = 750_000, durationMs = 2_700_000, method = PlayMethod.DIRECT_PLAY, previous = null, next = id(102),
)

internal val appearance = SubtitleAppearance(100, SubtitleColour.WHITE, SubtitleBackground.SHADED, SubtitleEdge.OUTLINE, keepFileStyling = true, bottomMarginPercent = 8)

internal fun ui(playback: PlaybackState = playingEpisode) = PlayerUiState(session = BEN, playback = playback, appearance = appearance)

/** Records every action by name ("seek:600000", "audio:2"...). */
internal class ActionLog {
    val calls = mutableListOf<String>()
    val actions = PlayerActions(
        onBack = { calls += "back" }, onTapSurface = { calls += "tap" }, onPlayPause = { calls += "playPause" },
        onSeek = { calls += "seek:$it" }, onSkipBack = { calls += "skipBack" }, onSkipForward = { calls += "skipForward" },
        onDoubleTap = { calls += "double:$it" }, onPrevious = { calls += "previous" }, onNext = { calls += "next" },
        onSkipSegment = { calls += "skipSegment" }, onPlayUpNext = { calls += "playUpNext" }, onCancelUpNext = { calls += "cancelUpNext" },
        onToggleLock = { calls += "lock" }, onOpenSheet = { calls += "sheet:$it" }, onCloseSheet = { calls += "closeSheet" },
        onSelectAudio = { calls += "audio:$it" }, onSelectSubtitle = { calls += "subtitle:$it" }, onBurnIn = { calls += "burnIn:$it" },
        onQuality = { calls += "quality:$it" }, onSpeed = { calls += "speed:$it" }, onSleep = { calls += "sleep:$it" },
        onAppearance = { calls += "appearance:${it.sizePercent}" }, onDelay = { calls += "delay:$it" }, onRetry = { calls += "retry" },
        onSignIn = { calls += "signIn" }, onDismissSubtitleNotice = { calls += "dismissNotice" }, onDismissSleepFired = { calls += "dismissSleep" },
    )
}

const val PLAYER_PHONE = "w640dp-h360dp"
const val PLAYER_TABLET = "w1200dp-h840dp"
