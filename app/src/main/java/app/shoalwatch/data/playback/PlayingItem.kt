package app.shoalwatch.data.playback

import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.playback.engine.CueTimeline
import app.shoalwatch.data.playback.engine.SubtitleFrame
import app.shoalwatch.data.playback.api.PlaybackGateway
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

/**
 * The item [PlaybackController] is playing now, and everything that belongs to it alone; dropped as a whole when the item
 * changes or playback ends. [knownMs]: the last position believed (the start, a seek target, or read while really playing).
 */
internal class PlayingItem(val itemId: String) {
    var entry: ItemWithUserData? = null
    var resolved: Resolved? = null
    var reporter: ProgressReporter? = null
    var segments: List<MediaSegment> = emptyList()
    var around = EpisodeNavigator.Around(null, null)
    var knownMs = 0L
    var finished = false
    var fellBack = false
    var reResolved = false
    var reroutes = 0
    var upNext: UpNextCountdown? = null
    var upNextShown = false

    // Tracks and subtitles (TrackControl): this item's alone, dropped with it.
    var remembered: TrackChoice? = null
    /** What to remember for the series after the user's next change: the remembered choice, then their changes. */
    var choice: TrackChoice? = null
    var subtitle: SubtitleChoice = SubtitleChoice.Off
    var timeline: CueTimeline? = null
    var subtitleLoad: Job? = null
    var pictureFrame: SubtitleFrame = SubtitleFrame.NONE
    var delayMs = 0
    var notice: SubtitleNotice? = null
    var effectsNoticeGiven = false
    /** A conversion was asked for because the stream's audio tracks did not match the server's (once per item). */
    var audioForced = false

    fun outro(): MediaSegment? = segments.firstOrNull { it.kind == SegmentKind.OUTRO }

    fun segmentAt(): MediaSegment? = segments.firstOrNull { knownMs >= it.startMs && knownMs < it.endMs }

    /** The engine's duration once known, else the plan's runtime, else the library's. */
    fun durationMs(engineMs: Long?): Long? = engineMs ?: resolved?.plan?.runtimeMs ?: entry?.item?.runtimeMs

    override fun toString() = "PlayingItem(<redacted>)"
}

/** Segments are optional: a failure is none, and leaves the item without skip buttons. */
internal suspend fun PlaybackGateway.segmentsOrNull(s: Session, itemId: String): List<MediaSegment>? = try {
    segments(s, itemId)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    null
}
