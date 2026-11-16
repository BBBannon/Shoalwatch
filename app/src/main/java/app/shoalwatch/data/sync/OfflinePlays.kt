package app.shoalwatch.data.sync

import app.shoalwatch.core.AppLog
import app.shoalwatch.data.db.downloads.OfflinePlayDao
import app.shoalwatch.data.db.library.ItemDao
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException

/**
 * The `offline_plays` record of every LOCAL play, with the phone's own dates (SPEC §3.2): one row per profile and item, written
 * by every LOCAL report so process death keeps the position. Watched is never undone by a later unfinished stop. Each stop is
 * also an outbox row (`LocalPlaystate.recordLocalStop`); a row is deleted once a confirmed outbox row covers it
 * (`PlaystateLanding`), and with the account (foreign key), and is kept when the download goes. A malformed id writes nothing.
 */
class OfflinePlays internal constructor(
    private val dao: OfflinePlayDao,
    private val now: () -> Long,
    private val outbox: OutboxStore,
    private val items: ItemDao,
) {
    /** Every progress report (10 s) and pause, and the start. */
    suspend fun position(s: Session, itemId: String, positionMs: Long) {
        val id = idOf(itemId) ?: return
        dao.position(s.serverId, s.userId, id, positionMs.coerceAtLeast(0), now())
    }

    suspend fun stopped(s: Session, itemId: String, positionMs: Long, finished: Boolean) {
        val id = idOf(itemId) ?: return
        dao.stopped(s.serverId, s.userId, id, positionMs.coerceAtLeast(0), finished, now())
    }

    /**
     * At start, before anything plays: an offline_plays row no outbox row covers is queued in an idempotent form (played → PLAYED
     * true; else POSITION), at the row's own time, marked as from an offline play so its landing deletes it. Never a PLAY, so it
     * can never add a play count. Covers a process that died mid-play (positions every 10 s, no stop) and the instant between a
     * stop's two writes. A newer unsent change of the same kind for the item is left as it is (nothing queued). Returns how many were queued; a row that cannot be queued is logged by class and left for next time.
     */
    suspend fun recoverUnqueued(): Int = dao.unqueued().count { p ->
        val kind = items.getItemEntity(p.serverId, p.itemId)?.kind ?: ItemKind.OTHER
        val change = if (p.played) {
            Change(p.itemId, kind, ChangeKind.PLAYED, flag = true, occurredAt = p.updatedAt, fromOfflinePlay = true)
        } else {
            Change(p.itemId, kind, ChangeKind.POSITION, positionMs = p.positionMs, occurredAt = p.updatedAt, fromOfflinePlay = true)
        }
        try {
            // Never over a newer unsent change of the same kind (Tasks 6-8 review M2): that one is what the user did last.
            outbox.enqueueUnlessNewer(Session(p.serverId, p.userId), change, OutboxStore.Marks(listOf(p.itemId), emptyList()), REFRESH) != null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            AppLog.w(TAG, "An offline play could not be queued", e)
            false
        }
    }

    private fun idOf(raw: String): String? = JellyfinId.parseOrNull(raw)?.value?.takeIf { it == raw }

    private companion object {
        const val TAG = "OfflinePlays"
        val REFRESH = listOf("resume", "nextUp")
    }
}
