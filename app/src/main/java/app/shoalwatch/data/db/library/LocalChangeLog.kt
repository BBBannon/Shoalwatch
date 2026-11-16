package app.shoalwatch.data.db.library

import app.shoalwatch.core.MonotonicClock
import app.shoalwatch.data.servers.Session
import java.util.concurrent.atomic.AtomicLong

/**
 * What the app changed locally, kept in memory for this process only (so nothing here is ever a source of truth): the rule
 * that stops a slow server answer undoing a change made after it was asked for.
 *
 * **Ordering is by a sequence number, never by time.** A local change takes the next number ([record]); a request takes the
 * current number just BEFORE it is sent ([stamp]); an answer is older than a change, and must not overwrite that item's user
 * data ([newerThan]) nor put the item back into a list an action took it out of ([removedFrom]), exactly when the change's
 * number is greater than the request's stamp. A request stamped at or after the change applies; one stamped before is dropped. The wall
 * clock plays no part, so setting it back or forward changes nothing.
 *
 * Entries are forgotten after [TTL_MS] by the [monotonic] clock (an answer that old would have timed out long since; a
 * clock change cannot expire them early), and at most [MAX_ENTRIES] are kept, oldest first out, so it cannot grow. If the cap ever
 * evicts an entry, protection for that item ends early; that needs more than 5,000 distinct items changed within 10 minutes.
 * One instance is made in `AppGraph` and shared by everything that writes server data or user changes. Thread-safe.
 */
class LocalChangeLog(val monotonic: MonotonicClock) {
    private data class Key(val serverId: String, val userId: String, val itemId: String)

    private class Entry(var seq: Long, var atMs: Long, val removedFrom: MutableSet<String>)

    private val sequence = AtomicLong()

    /** Oldest first: an entry that is recorded again moves to the end. */
    private val entries = LinkedHashMap<Key, Entry>()

    val size: Int get() = synchronized(entries) { entries.size }

    /** The stamp a request takes immediately before it is sent. */
    fun stamp(): Long = sequence.get()

    /** Notes that this user's data for [itemId] was just changed, and (if given) that it was taken out of list [removedFrom]. */
    fun record(s: Session, itemId: String, removedFrom: String? = null) = synchronized(entries) {
        val now = monotonic.nowMs()
        prune(now)
        val key = Key(s.serverId, s.userId, itemId)
        val entry = entries.remove(key) ?: Entry(0, now, mutableSetOf())
        entry.seq = sequence.incrementAndGet()
        entry.atMs = now
        if (removedFrom != null) entry.removedFrom += removedFrom
        entries[key] = entry
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
    }

    /** True when the item was changed locally AFTER a request stamped [requestedAtSeq] was sent: the answer is older than the change. */
    fun newerThan(s: Session, itemId: String, requestedAtSeq: Long): Boolean =
        synchronized(entries) { (live(Key(s.serverId, s.userId, itemId))?.seq ?: Long.MIN_VALUE) > requestedAtSeq }

    /** True when this process has changed the item for this user recently (so any marker on it may belong to a live action). */
    fun touched(s: Session, itemId: String): Boolean = synchronized(entries) { live(Key(s.serverId, s.userId, itemId)) != null }

    /** True when a local action took the item out of [listKey] (and has not put it back). */
    fun removedFrom(s: Session, itemId: String, listKey: String): Boolean =
        synchronized(entries) { live(Key(s.serverId, s.userId, itemId))?.removedFrom?.contains(listKey) == true }

    /** The entry was put back (a rollback): the server's own answers about that list are authoritative again. */
    fun forgetRemoval(s: Session, itemId: String, listKey: String) = synchronized(entries) {
        entries[Key(s.serverId, s.userId, itemId)]?.removedFrom?.remove(listKey)
        Unit
    }

    private fun live(key: Key): Entry? = entries[key]?.takeIf { monotonic.nowMs() - it.atMs <= TTL_MS }

    private fun prune(now: Long) {
        val it = entries.values.iterator()
        while (it.hasNext() && now - it.next().atMs > TTL_MS) it.remove()
    }

    companion object {
        const val TTL_MS = 10 * 60 * 1000L
        const val MAX_ENTRIES = 5_000
    }
}
