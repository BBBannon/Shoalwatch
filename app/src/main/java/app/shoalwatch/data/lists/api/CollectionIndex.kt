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
 * shared between profiles. A failed read keeps nothing. Reads are single-flight per index.
 */
internal class CollectionIndex(private val clock: MonotonicClock) {
    class Snapshot(val at: Long, val held: LinkedHashMap<String, HeldCollection>)

    private val lock = Any()
    private val snapshots = HashMap<Session, Snapshot>()
    private val loading = Mutex()

    /** The collections directly holding [itemId]; [load] reads the whole index and runs only when there is no fresh one. */
    suspend fun of(s: Session, itemId: String, load: suspend () -> List<HeldCollection>): List<CollectionRef> {
        fresh(s)?.let { return it.holding(itemId) }
        return loading.withLock {
            val snapshot = fresh(s) ?: run {
                val held = LinkedHashMap<String, HeldCollection>()
                for (c in load().take(ListsLimits.INDEX_COLLECTIONS)) held.putIfAbsent(c.id, c)
                Snapshot(clock.nowMs(), held).also { synchronized(lock) { snapshots[s] = it } }
            }
            snapshot.holding(itemId)
        }
    }

    /** An add made through the seam: the index (if one is kept) learns it now. */
    fun added(s: Session, collectionId: String, itemIds: List<String>) {
        synchronized(lock) {
            val c = snapshots[s]?.held?.get(collectionId) ?: return
            for (id in itemIds) if (c.children.size < ListsLimits.INDEX_CHILDREN) c.children += id
        }
    }

    /** A remove made through the seam. */
    fun removed(s: Session, collectionId: String, itemIds: List<String>) {
        synchronized(lock) { snapshots[s]?.held?.get(collectionId)?.children?.removeAll(itemIds.toSet()) }
    }

    /** A collection made or a list otherwise changed: the next ask reads again. */
    fun forget(s: Session) {
        synchronized(lock) { snapshots.remove(s) }
    }

    private fun fresh(s: Session): Snapshot? = synchronized(lock) {
        val snapshot = snapshots[s] ?: return@synchronized null
        if (clock.nowMs() - snapshot.at >= ListsLimits.INDEX_TTL_MS) snapshots.remove(s).let { null } else snapshot
    }

    private fun Snapshot.holding(itemId: String): List<CollectionRef> = synchronized(lock) {
        held.values.filter { itemId in it.children }.map { CollectionRef(it.id, it.name) }
    }
}
