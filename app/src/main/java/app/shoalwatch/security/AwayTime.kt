package app.shoalwatch.security

/**
 * One trip away from the app, for [LockGate]'s "Lock after" (rule 2): the time that counts is the time away while nothing was
 * playing. A video playing in the picture-in-picture window holds the clock; a pause, the end, a failure or the player's release
 * starts it again, and the paused time adds up across every pause of the trip (pausing and playing again never resets it). Times
 * are the gate's monotonic clock. Not thread-safe: [LockGate] calls it holding its lock.
 */
internal class AwayTime {
    private var playing = false
    private var away = false
    private var countedMs = 0L
    private var countingSince: Long? = null // null while the clock is held (playing) or there is no trip

    /** Leaving at [now]. A second leave during the same trip keeps the time so far. */
    fun leave(now: Long) {
        if (away) return
        away = true
        countedMs = 0L
        countingSince = if (playing) null else now
    }

    fun setPlaying(playing: Boolean, now: Long) {
        this.playing = playing
        if (!away) return
        val since = countingSince
        if (playing && since != null) {
            countedMs += now - since
            countingSince = null
        } else if (!playing && since == null) {
            countingSince = now
        }
    }

    /** Back at [now]: the time that counted, or null when there was no trip. */
    fun back(now: Long): Long? {
        if (!away) return null
        away = false
        val total = countedMs + (countingSince?.let { now - it } ?: 0L)
        countedMs = 0L
        countingSince = null
        return total
    }
}
