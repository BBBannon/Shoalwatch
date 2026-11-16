package app.shoalwatch.data.library

import app.shoalwatch.data.db.downloads.OfflinePlayDao
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.sync.OutboxStore
import app.shoalwatch.data.sync.SyncLanding

/**
 * What this phone does when the sender is done with a row (see "Tidy-up actions" in docs/ARCHITECTURE.md). Under the library
 * write gate, in ONE transaction: the row goes ([OutboxStore.take]); every item it marked that no other row still marks has its
 * marker cleared and is noted in `LocalChangeLog` again, with the lists it left (so a refresh asked for before this moment
 * cannot overwrite it); an offline play the row carried is deleted once covered; `lastSyncedAt` is set. Then the lists the
 * server recomputes are refreshed, and when the server's copy won ([overruled]) the item too, so its copy replaces the local one.
 * A row already gone (a newer change replaced it while it was in flight) is nothing to do: the newer row carries it.
 */
internal class PlaystateLanding(
    private val library: LibraryRepository,
    private val outbox: OutboxStore,
    private val offlinePlays: OfflinePlayDao,
    private val clock: () -> Long,
) : SyncLanding {
    override suspend fun confirmed(s: Session, seq: Long) = land(s, seq, overruled = false)

    override suspend fun overruled(s: Session, seq: Long) = land(s, seq, overruled = true)

    private suspend fun land(s: Session, seq: Long, overruled: Boolean) {
        val store = library.store
        val landed = library.writeGated {
            store.inTransaction {
                val row = outbox.take(seq) ?: return@inTransaction null
                for (itemId in row.unmarked) {
                    store.clearPending(s, itemId)
                    store.noteChanged(s, itemId)
                }
                row.leftLists.filter { (itemId, _) -> itemId in row.unmarked }.forEach { (itemId, list) -> store.noteChanged(s, itemId, removedFrom = list) }
                if (row.fromOfflinePlay) offlinePlays.deleteCovered(s.serverId, s.userId, row.itemId, row.occurredAt)
                outbox.touchSynced(s)
                row
            }
        } ?: return
        val lists = landed.refreshKeys.mapNotNull(::parseRecompute).toMutableSet()
        if (overruled) lists += Recompute.Item(landed.itemId)
        library.refreshInBackground(s, lists)
    }
}
