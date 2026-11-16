package app.shoalwatch.data.sync

import app.shoalwatch.data.servers.Session

/**
 * The items whose stop report is on its way to the server right now, per profile (memory only; Tasks 9-10 review I1). The
 * outbox drain passes over their rows (and every later row of the same item) until the report lands or fails, so a finished
 * stop's PLAYED tick can never reach the server before the report itself, which could count the play twice or leave a resume
 * point beside "played". After a process death nothing is held, so the next start sends the rows. Two holds need two releases.
 */
internal class StopsInFlight {
    private val held = HashMap<Session, HashMap<String, Int>>()

    fun hold(s: Session, itemId: String) = synchronized(this) { held.getOrPut(s) { HashMap() }.merge(itemId, 1, Int::plus); Unit }

    fun release(s: Session, itemId: String) = synchronized(this) {
        val items = held[s] ?: return@synchronized
        val left = (items[itemId] ?: return@synchronized) - 1
        if (left > 0) items[itemId] = left else items.remove(itemId)
        if (items.isEmpty()) held.remove(s)
    }

    /** What [s]'s drain must pass over now. */
    fun of(s: Session): Set<String> = synchronized(this) { held[s]?.keys?.toSet().orEmpty() }
}
