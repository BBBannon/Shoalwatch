package app.shoalwatch.data.playback

/**
 * The "Up next" countdown for [itemId], pure. With autoplay it counts [totalMs] of time the player is not paused (the
 * controller calls [advance] on each tick that is not paused) and is then [due]; without autoplay it waits for the user.
 * [cancel] stops it for good: nothing auto-plays.
 */
class UpNextCountdown(val itemId: String, val title: String?, autoplay: Boolean, totalMs: Long = 10_000) {
    private var remainingMs: Long? = totalMs.takeIf { autoplay }
    private var cancelled = false

    fun advance(ms: Long) {
        remainingMs = remainingMs?.let { (it - ms).coerceAtLeast(0) }
    }

    fun cancel() {
        cancelled = true
        remainingMs = null
    }

    val due: Boolean get() = remainingMs == 0L

    /** Seconds left rounded up, so the card shows 10 for the whole first second. */
    val state: UpNextState get() = UpNextState(itemId, title, remainingMs?.let { ((it + 999) / 1000).toInt() }, cancelled)
}

/** Up next for the next episode, once per item (no next episode: nothing); not when the sleep timer stops at the end ([sleepAtEnd]). */
internal fun PlayingItem.offerUpNext(sleepAtEnd: Boolean, autoplay: () -> Boolean) {
    if (upNextShown || sleepAtEnd) return
    val next = around.next ?: return
    upNextShown = true
    upNext = UpNextCountdown(next.id, next.name, autoplay())
}

/** A seek to [toMs], before where Up next appears (the Outro's start, else the end), withdraws it. */
internal fun PlayingItem.withdrawUpNextBefore(toMs: Long, durationMs: Long?) {
    if (upNextShown && toMs < (outro()?.startMs ?: durationMs ?: Long.MAX_VALUE)) { upNext = null; upNextShown = false }
}
