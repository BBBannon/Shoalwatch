package app.shoalwatch.data.downloads

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

/** What "Watched together" did, and whether a protected sharer was left out of it (security review M8 F12, review M-1). */
data class TogetherRelease(val outcome: ReleaseOutcome, val leftOut: Boolean)

/**
 * Proofs that a protected sharer's own PIN was entered for one "Watched together" press: made by [record] after a right PIN, for
 * exactly (releaser, sharer, item), used once by [consume]. A proof lives only as long as its press (review M-1/M-2): the next
 * press and every release of that (releaser, item) [drop] whatever is left, so it never expires mid-flow and is never reused.
 * Memory only.
 */
internal class TogetherProofs {
    private data class Key(val by: Session, val sharer: Session, val itemId: String)

    private val proofs = HashSet<Key>() // guarded by this

    @Synchronized fun record(by: Session, sharer: Session, itemId: String) {
        proofs += Key(by, sharer, itemId)
    }

    /** True once, for exactly this releaser, sharer and item. */
    @Synchronized fun consume(by: Session, sharer: Session, itemId: String): Boolean = proofs.remove(Key(by, sharer, itemId))

    /** Every proof of [by]'s press on [itemId] goes. */
    @Synchronized fun drop(by: Session, itemId: String) {
        proofs.removeAll { it.by == by && it.itemId == itemId }
    }

    override fun toString() = "TogetherProofs(<redacted>)"
}
