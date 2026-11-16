package app.shoalwatch.data.sync

import app.shoalwatch.data.db.downloads.OfflinePlayDao
import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The `offline_plays` record Milestone 5's outbox sends, with the phone's own dates (SPEC §3.2): one row per profile and item,
 * written by every LOCAL report so process death keeps the position. Watched is never undone by a later unfinished stop.
 * Deleted with the account (foreign key), kept when the download goes. A malformed id writes nothing.
 */
class OfflinePlays internal constructor(private val dao: OfflinePlayDao, private val now: () -> Long) {
    /** Every progress report (10 s) and pause, and the start. */
    suspend fun position(s: Session, itemId: String, positionMs: Long) {
        val id = idOf(itemId) ?: return
        dao.position(s.serverId, s.userId, id, positionMs.coerceAtLeast(0), now())
    }

    suspend fun stopped(s: Session, itemId: String, positionMs: Long, finished: Boolean) {
        val id = idOf(itemId) ?: return
        dao.stopped(s.serverId, s.userId, id, positionMs.coerceAtLeast(0), finished, now())
    }

    /** How many plays this profile has waiting to be sent (Milestone 5's "waiting to sync"). */
    fun pending(s: Session): Flow<Int> = if (idOf(s.userId) == null) flowOf(0) else dao.count(s.serverId, s.userId)

    private fun idOf(raw: String): String? = JellyfinId.parseOrNull(raw)?.value?.takeIf { it == raw }
}
