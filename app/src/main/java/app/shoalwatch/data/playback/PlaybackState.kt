package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.Session

/**
 * What the player screen sees ([PlaybackController.state]). Carries no URL, route, token or Media3 type. [title] and
 * [episodeLine] come from the Library seam and are already display-safe; [episodeLine] is the series name for an episode (the
 * screen adds season and episode numbers) and null for a film. Never printed (it holds a title and a position).
 */
data class PlaybackState(
    val session: Session?,
    val itemId: String?,
    val title: String?, val episodeLine: String?,
    val phase: Phase,
    val positionMs: Long, val durationMs: Long?, val bufferedMs: Long,
    val method: PlayMethod?, val transcodeReasons: Set<TranscodeReason>, val videoRange: VideoRange?,
    val quality: Quality, val speed: Float,
    val audioTracks: List<AudioTrack>, val audioIndex: Int?,
    val subtitleTracks: List<SubtitleTrack>, val subtitle: SubtitleChoice,
    /** Set while the position is inside an Intro, Outro, Recap, Preview or Commercial segment. */
    val segmentNow: MediaSegment?,
    val upNext: UpNextState?,
    /** Item ids from [EpisodeNavigator]. */
    val previous: String?, val next: String?,
    val sleep: SleepState,
    val error: AppError.Kind?,
    val fellBackToTranscode: Boolean,
) {
    enum class Phase { IDLE, RESOLVING, BUFFERING, PLAYING, PAUSED, ENDED, FAILED }

    override fun toString() = "PlaybackState(phase=$phase)"

    companion object {
        /** Nothing playing, nobody's: what a controller publishes before [PlaybackController.open] and after it ends. */
        val EMPTY = PlaybackState(
            session = null, itemId = null, title = null, episodeLine = null, phase = Phase.IDLE,
            positionMs = 0, durationMs = null, bufferedMs = 0, method = null, transcodeReasons = emptySet(), videoRange = null,
            quality = Quality.MAXIMUM, speed = 1f, audioTracks = emptyList(), audioIndex = null, subtitleTracks = emptyList(),
            subtitle = SubtitleChoice.Off, segmentNow = null, upNext = null, previous = null, next = null, sleep = SleepState.Off,
            error = null, fellBackToTranscode = false,
        )
    }
}

sealed interface SubtitleChoice {
    data object Off : SubtitleChoice
    data class On(val index: Int, val delivery: SubtitleDelivery) : SubtitleChoice
}

/** [secondsLeft] null: waiting for the user (no autoplay, or the countdown was cancelled). */
data class UpNextState(val itemId: String, val title: String?, val secondsLeft: Int?, val cancelled: Boolean)

sealed interface SleepState {
    data object Off : SleepState
    data class Minutes(val endsAtMonoMs: Long) : SleepState
    data object EndOfItem : SleepState
}
