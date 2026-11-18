package app.shoalwatch.data.downloads

import app.shoalwatch.core.MonotonicClock
import app.shoalwatch.data.servers.Session
import app.shoalwatch.security.PinCheck

/**
 * What "Watched together" needs of profile protection (security review M8 F12): whether a sharer is protected, that profile's
 * own PIN check (counted and throttled like any other try; it zeroes the PIN), and its name for the PIN dialog.
 */
class SharerLocks(
    val isProtected: suspend (Session) -> Boolean,
    val check: suspend (Session, CharArray) -> PinCheck,
    val name: suspend (Session) -> String?,
) {
    override fun toString() = "SharerLocks(<redacted>)"
}

/**
 * Proofs that a protected sharer's own PIN was entered for one "Watched together" press: made by [record] after a right PIN, for
 * exactly (releaser, sharer, item), used once by [consume] within [VALID_MS] of [clock] (monotonic). Memory only.
 */
internal class TogetherProofs(private val clock: MonotonicClock) {
    private data class Key(val by: Session, val sharer: Session, val itemId: String)

    private val proofs = HashMap<Key, Long>() // guarded by this

    @Synchronized fun record(by: Session, sharer: Session, itemId: String) {
        proofs[Key(by, sharer, itemId)] = clock.nowMs()
    }

    /** True once, within [VALID_MS] of [record] for exactly this press. */
    @Synchronized fun consume(by: Session, sharer: Session, itemId: String): Boolean {
        val at = proofs.remove(Key(by, sharer, itemId)) ?: return false
        return clock.nowMs() - at in 0..VALID_MS
    }

    override fun toString() = "TogetherProofs(<redacted>)"

    companion object {
        const val VALID_MS = 60_000L
    }
}
