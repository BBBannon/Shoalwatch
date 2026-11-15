package app.shoalwatch.data.playback

import app.shoalwatch.data.library.ItemWithUserData

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

    fun outro(): MediaSegment? = segments.firstOrNull { it.kind == SegmentKind.OUTRO }

    fun segmentAt(): MediaSegment? = segments.firstOrNull { knownMs >= it.startMs && knownMs < it.endMs }

    /** The engine's duration once known, else the plan's runtime, else the library's. */
    fun durationMs(engineMs: Long?): Long? = engineMs ?: resolved?.plan?.runtimeMs ?: entry?.item?.runtimeMs

    override fun toString() = "PlayingItem(<redacted>)"
}
