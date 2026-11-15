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
    /** Per item, -10,000..+10,000 ms (later is positive); only for a FILE subtitle, which [PlaybackController.cues] draws. */
    val subtitleDelayMs: Int,
    /** The file subtitle in use has ASS effects the phone cannot draw: offer "Let the server draw these subtitles". */
    val subtitleEffectsNotDrawn: Boolean,
    /** To tell the user once, until [PlaybackController.dismissSubtitleNotice]. */
    val subtitleNotice: SubtitleNotice?,
    /** Set while the position is inside an Intro, Outro, Recap, Preview or Commercial segment. */
    val segmentNow: MediaSegment?,
    val upNext: UpNextState?,
    /** Item ids from [EpisodeNavigator]. */
    val previous: String?, val next: String?,
    val sleep: SleepState,
    val error: AppError.Kind?,
    val fellBackToTranscode: Boolean,
    /** The controller has ended (closed, refused, signed out, another profile took over): the screen leaves. Never true before. */
    val closed: Boolean = false,
) {
    enum class Phase { IDLE, RESOLVING, BUFFERING, PLAYING, PAUSED, ENDED, FAILED }

    override fun toString() = "PlaybackState(phase=$phase)"

    companion object {
        /** Nothing playing, nobody's: what a controller publishes before [PlaybackController.open]. */
        val EMPTY = PlaybackState(
            session = null, itemId = null, title = null, episodeLine = null, phase = Phase.IDLE,
            positionMs = 0, durationMs = null, bufferedMs = 0, method = null, transcodeReasons = emptySet(), videoRange = null,
            quality = Quality.MAXIMUM, speed = 1f, audioTracks = emptyList(), audioIndex = null, subtitleTracks = emptyList(),
            subtitle = SubtitleChoice.Off, subtitleDelayMs = 0, subtitleEffectsNotDrawn = false, subtitleNotice = null, segmentNow = null, upNext = null, previous = null, next = null, sleep = SleepState.Off,
            error = null, fellBackToTranscode = false,
        )

        /** [EMPTY], and closed: what an ended controller publishes, so a screen can tell it from "not opened yet" (Task 6 review M-1). */
        val CLOSED = EMPTY.copy(closed = true)
    }
}

/** What [PlaybackController] publishes while it plays [c] (null: nothing yet). */
internal fun stateOf(
    session: Session, c: PlayingItem?, phase: PlaybackState.Phase, durationMs: Long?, bufferedMs: Long, quality: Quality, speed: Float,
    sleep: SleepState, error: AppError.Kind?,
): PlaybackState {
    val plan = c?.resolved?.plan
    val subtitle = c?.subtitle ?: SubtitleChoice.Off
    return PlaybackState(
        session = session, itemId = c?.itemId, title = c?.entry?.item?.name, episodeLine = c?.entry?.item?.seriesName,
        phase = phase, positionMs = c?.knownMs ?: 0, durationMs = durationMs, bufferedMs = bufferedMs,
        method = plan?.method, transcodeReasons = plan?.transcodeReasons.orEmpty(), videoRange = plan?.videoRange,
        quality = quality, speed = speed, audioTracks = plan?.audioTracks.orEmpty(), audioIndex = plan?.audioIndex,
        subtitleTracks = plan?.subtitleTracks.orEmpty(), subtitle = subtitle, subtitleDelayMs = c?.delayMs ?: 0,
        subtitleEffectsNotDrawn = (subtitle as? SubtitleChoice.On)?.delivery == SubtitleDelivery.FILE && c?.timeline?.usesEffectsThePhoneCannotDraw == true,
        subtitleNotice = c?.notice,
        segmentNow = c?.segmentAt(), upNext = c?.upNext?.state, previous = c?.around?.previous?.id, next = c?.around?.next?.id,
        sleep = sleep, error = error, fellBackToTranscode = c?.fellBack == true,
    )
}

/** UNREADABLE: "These subtitles couldn't be read." (subtitles are off). EFFECTS_NOT_DRAWN: some effects are not drawn (once per item). */
enum class SubtitleNotice { UNREADABLE, EFFECTS_NOT_DRAWN }

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
