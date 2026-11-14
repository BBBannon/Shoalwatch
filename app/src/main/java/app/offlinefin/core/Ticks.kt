package app.offlinefin.core

/** Jellyfin durations are in 100-nanosecond ticks (10,000 per millisecond). */
object Ticks {
    private const val TICKS_PER_MS = 10_000L

    fun toMs(ticks: Long): Long = ticks / TICKS_PER_MS
    fun fromMs(ms: Long): Long = ms * TICKS_PER_MS
}
