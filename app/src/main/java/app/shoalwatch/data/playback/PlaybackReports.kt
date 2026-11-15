package app.shoalwatch.data.playback

/**
 * One report of where playback is, for the server's playstate endpoints. Ids are the validated ones from the plan
 * (`itemId` a normalised `JellyfinId`, `mediaSourceId` and `playSessionId` `[A-Za-z0-9]{1,64}`); the gateway checks
 * them again and sends nothing if they fail. Never printed: it holds an item and a position.
 *
 * Task 5 of Milestone 3 adds the `PlaybackReports` seam to this file.
 */
data class PlaybackReport(
    val itemId: String, val mediaSourceId: String, val playSessionId: String?, val method: PlayMethod,
    val positionMs: Long, val paused: Boolean, val audioIndex: Int?, val subtitleIndex: Int?, val failed: Boolean = false,
) { override fun toString() = "PlaybackReport(<redacted>)" }
