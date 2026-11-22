package app.shoalwatch.data.lists.api

import app.shoalwatch.core.MonotonicClock
import app.shoalwatch.data.lists.CollectionRef
import app.shoalwatch.data.lists.ListsLimits
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One collection as the index holds it: its name and its direct children (ids only). */
internal class HeldCollection(val id: String, val name: String, val children: MutableSet<String>)

/**
 * "Part of this collection" (Milestone 11, decision 7): Jellyfin has no "which collections hold this item" query, so each profile's
 * collections (at most [ListsLimits.INDEX_COLLECTIONS]) and each one's direct children (at most [ListsLimits.INDEX_CHILDREN]) are
 * read once, when first asked, and kept in memory for [ListsLimits.INDEX_TTL_MS] on the monotonic clock. Never written to disk, never
 * shared between profiles. A failed read keeps nothing. Reads are single-flight per profile (one lock each), and a change made while a read is in flight is
 * recorded and applied to what the read returns, so it is never lost (a `forget` stops that read from being kept at all). An add to a
 * collection already at [ListsLimits.INDEX_CHILDREN] is still recorded (up to [ListsLimits.INDEX_CHILDREN_HARD]), never dropped.
 */
internal class CollectionIndex(private val clock: MonotonicClock) {
    class Snapshot(val at: Long, val held: LinkedHashMap<String, HeldCollection>)

    private sealed interface Change {
        class Add(val id: String, val items: List<String>) : Change
        class Remove(val id: String, val items: List<String>) : Change
        object Forget : Change
    }

    /** One profile's state. [mutex] makes its reads single-flight; the rest is guarded by [lock]. */
    private class Slot {
        val mutex = Mutex()
        var snapshot: Snapshot? = null
        var loading = false
        val pending = ArrayList<Change>()
    }

    private val lock = Any()
    private val slots = HashMap<Session, Slot>()

    private fun slot(s: Session) = synchronized(lock) { slots.getOrPut(s) { Slot() } }

    /** The collections directly holding [itemId]; [load] reads the whole index and runs only when there is no fresh one. */
    suspend fun of(s: Session, itemId: String, load: suspend () -> List<HeldCollection>): List<CollectionRef> {
        val slot = slot(s)
        fresh(slot)?.let { return holding(it, itemId) }
        return slot.mutex.withLock {
            fresh(slot)?.let { return@withLock holding(it, itemId) }
            synchronized(lock) { slot.loading = true; slot.pending.clear() }
            val held = LinkedHashMap<String, HeldCollection>()
            try {
                for (c in load().take(ListsLimits.INDEX_COLLECTIONS)) held.putIfAbsent(c.id, c)
            } catch (e: Throwable) {
                synchronized(lock) { slot.loading = false; slot.pending.clear() }
                throw e
            }
            val snapshot = Snapshot(clock.nowMs(), held)
            synchronized(lock) {
                var keep = true
                for (change in slot.pending) when (change) {
                    is Change.Add -> apply(snapshot, change)
                    is Change.Remove -> remove(snapshot, change)
                    Change.Forget -> keep = false // what was read may predate the change: answer from it, but keep nothing
                }
                slot.pending.clear()
                slot.loading = false
                slot.snapshot = if (keep) snapshot else null
            }
            holding(snapshot, itemId)
        }
    }

    /** An add made through the seam: the index learns it now, or when the read in flight ends. */
    fun added(s: Session, collectionId: String, itemIds: List<String>) = record(s, Change.Add(collectionId, itemIds))

    /** A remove made through the seam. */
    fun removed(s: Session, collectionId: String, itemIds: List<String>) = record(s, Change.Remove(collectionId, itemIds))

    /** A collection made or a list otherwise changed: nothing kept stays, and a read in flight is not kept. */
    fun forget(s: Session) = record(s, Change.Forget)

    private fun record(s: Session, change: Change) {
        synchronized(lock) {
            val slot = slots[s] ?: return
            if (slot.loading) slot.pending += change
            val snapshot = slot.snapshot ?: return
            when (change) {
                is Change.Add -> apply(snapshot, change)
                is Change.Remove -> remove(snapshot, change)
                Change.Forget -> slot.snapshot = null
            }
        }
    }

    private fun apply(snapshot: Snapshot, change: Change.Add) {
        val c = snapshot.held[change.id] ?: return
        for (id in change.items) if (c.children.size < ListsLimits.INDEX_CHILDREN_HARD) c.children += id
    }

    private fun remove(snapshot: Snapshot, change: Change.Remove) {
        snapshot.held[change.id]?.children?.removeAll(change.items.toSet())
    }

    private fun fresh(slot: Slot): Snapshot? = synchronized(lock) {
        val snapshot = slot.snapshot ?: return@synchronized null
        if (clock.nowMs() - snapshot.at >= ListsLimits.INDEX_TTL_MS) { slot.snapshot = null; null } else snapshot
    }

    private fun holding(snapshot: Snapshot, itemId: String): List<CollectionRef> = synchronized(lock) {
        snapshot.held.values.filter { itemId in it.children }.map { CollectionRef(it.id, it.name) }
    }
}
