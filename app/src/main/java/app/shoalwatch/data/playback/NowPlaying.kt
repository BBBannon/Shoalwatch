package app.shoalwatch.data.playback

/**
 * Whether any of the app's players is playing now (Media3's own `isPlaying`), for the app lock: "Lock after" does not count while
 * a video plays (see "App lock" in docs/ARCHITECTURE.md). Each engine gets its own [reporter], so an old player let go after a new
 * one has started cannot hide the new one. [onChange] hears each change once, as it happens.
 */
class NowPlaying(private val onChange: (Boolean) -> Unit) {
    private val playing = mutableSetOf<Any>()

    /** One engine's "playing now / not playing now". */
    fun reporter(): (Boolean) -> Unit {
        val engine = Any()
        return { now -> report(engine, now) }
    }

    private fun report(engine: Any, now: Boolean) = synchronized(this) {
        val before = playing.isNotEmpty()
        if (now) playing += engine else playing -= engine
        if (playing.isNotEmpty() != before) onChange(!before)
    }
}
