package app.shoalwatch.data.db.library

import app.shoalwatch.data.servers.Session

/**
 * What the app changed locally and when, kept in memory for this process only (so nothing here is ever a source of truth):
 * the rule that stops a slow server answer undoing a change made after it was asked for. An answer carries the time its
 * request STARTED; if the item was changed locally at or after that moment, the answer is older than the change and must
 * not overwrite the user's data for that item ([newerThan]), nor put the item back into a list the action took it out of
 * ([removedFrom]). The same millisecond counts as the change being newer.
 *
 * Entries older than [TTL_MS] are forgotten (an answer that old would have timed out long since), and at most [MAX_ENTRIES]
 * are kept (the oldest go first), so it cannot grow. Thread-safe. [clock] is the repository's injected clock (epoch ms).
 */
class LocalChangeLog(private val clock: () -> Long) {
    private data class Key(val serverId: String, val userId: String, val itemId: String)

    private class Entry(var at: Long, val removedFrom: MutableSet<String>)

    /** Oldest first: an entry that is recorded again moves to the end. */
    private val entries = LinkedHashMap<Key, Entry>()

    val size: Int get() = synchronized(entries) { entries.size }

    /** Notes that this user's data for [itemId] was just changed, and (if given) that it was taken out of list [removedFrom]. */
    fun record(s: Session, itemId: String, removedFrom: String? = null) = synchronized(entries) {
        val now = clock()
        prune(now)
        val key = Key(s.serverId, s.userId, itemId)
        val entry = entries.remove(key) ?: Entry(now, mutableSetOf())
        entry.at = now
        if (removedFrom != null) entry.removedFrom += removedFrom
        entries[key] = entry
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
    }

    /** When this user's data for the item was last changed locally, or null (never, or too long ago to matter). */
    fun changedAt(s: Session, itemId: String): Long? = synchronized(entries) { live(Key(s.serverId, s.userId, itemId))?.at }

    /** True when the item was changed locally at or after [fetchStartedAtMs]: an answer to a request that started then is older than the change. */
    fun newerThan(s: Session, itemId: String, fetchStartedAtMs: Long): Boolean = (changedAt(s, itemId) ?: Long.MIN_VALUE) >= fetchStartedAtMs

    /** True when a local action took the item out of [listKey] (and has not put it back). */
    fun removedFrom(s: Session, itemId: String, listKey: String): Boolean =
        synchronized(entries) { live(Key(s.serverId, s.userId, itemId))?.removedFrom?.contains(listKey) == true }

    /** The entry was put back (a rollback): the server's own answers about that list are authoritative again. */
    fun forgetRemoval(s: Session, itemId: String, listKey: String) = synchronized(entries) {
        entries[Key(s.serverId, s.userId, itemId)]?.removedFrom?.remove(listKey)
        Unit
    }

    private fun live(key: Key): Entry? = entries[key]?.takeIf { clock() - it.at <= TTL_MS }

    private fun prune(now: Long) {
        val it = entries.values.iterator()
        while (it.hasNext() && now - it.next().at > TTL_MS) it.remove()
    }

    companion object {
        const val TTL_MS = 10 * 60 * 1000L
        const val MAX_ENTRIES = 5_000
    }
}
