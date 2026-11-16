package app.shoalwatch.data.db.downloads

import androidx.room.withTransaction
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.downloads.ReleaseChoice
import app.shoalwatch.data.downloads.SharedDelete
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.servers.Session

/**
 * Watch-and-delete's one rule (see "Watch-and-delete" in docs/ARCHITECTURE.md), each call one transaction under [lock] (it
 * changes pins). SAFEGUARDING: a profile acts only on a download it may see (`OfflineLibraryDao.getPlayable`: COMPLETE, its own
 * `visible_items` record, and its own or shared); anything else is [Result.NotYours], exactly as for no download at all.
 *
 * - **Sharing off:** only this profile's claim goes; the files go with the last owner.
 * - **Sharing on, "After anyone has watched":** gone for everyone.
 * - **Sharing on, "After everyone has watched":** gone once every account that owns it or was shown it here has watched it on
 *   this phone; until then nothing changes.
 *
 * When it goes, an episode leaves a tombstone (`released_episodes`) for this profile and every owner, so the show still browses
 * offline with its pins and artwork; a film leaves none. The files are the caller's to delete ([Result.Deleted]). [now] is epoch ms.
 */
internal class ReleaseStore(private val db: AppDatabase, private val now: () -> Long, private val lock: LibraryWriteLock) {
    sealed interface Result {
        data class Deleted(val key: DownloadKey, val storage: StorageKind, val volumeId: String?) : Result {
            override fun toString() = "Deleted(<redacted>)"
        }
        data object KeptForOthers : Result
        data object KeptUntilEveryoneWatched : Result
        data object NotYours : Result
    }

    private val dao = db.downloadDao()
    private val offline = db.offlineLibraryDao()
    private val housekeeping = db.housekeepingDao()

    /** One transaction under the lock. [s] must already be marked watched on this phone. */
    suspend fun release(s: Session, itemId: String): Result = lock.locked { db.withTransaction { decide(s, itemId) } }

    private suspend fun decide(s: Session, itemId: String): Result {
        val row = offline.getPlayable(s.serverId, s.userId, itemId) ?: return Result.NotYours
        if (dao.sharing(s.serverId) != true) {
            if (dao.deleteOwner(s.serverId, s.userId, itemId) == 0) return Result.NotYours
            if (dao.owners(s.serverId, itemId).isNotEmpty()) {
                tombstones(row, listOf(s.userId)) // its own library shows it watched and deleted; the other owners keep theirs
                return Result.KeptForOthers
            }
            return gone(row, listOf(s.userId))
        }
        val owners = dao.owners(s.serverId, itemId)
        if ((housekeeping.deleteShared(s.serverId) ?: SharedDelete.ANYONE) == SharedDelete.EVERYONE) {
            val everyone = (owners + dao.accountsShown(s.serverId, itemId, Long.MIN_VALUE, Long.MAX_VALUE) + s.userId).toSet()
            if (everyone.any { housekeeping.played(s.serverId, it, itemId) != true }) return Result.KeptUntilEveryoneWatched
        }
        return gone(row, (owners + s.userId).distinct())
    }

    /** The row goes (owners, tracks and segments cascade); tombstones first, so the pins recomputed after it keep the show. */
    private suspend fun gone(row: DownloadEntity, tombstoneFor: List<String>): Result {
        tombstones(row, tombstoneFor)
        dao.delete(row.serverId, row.itemId)
        dao.recomputePins(row.serverId, listOfNotNull(row.itemId, row.seasonId, row.seriesId).distinct())
        return Result.Deleted(DownloadKey(row.serverId, row.itemId), row.storage, row.volumeId)
    }

    /** An episode's tombstones for [users]; a film (or an episode with no series) has nothing to browse under it. */
    private suspend fun tombstones(row: DownloadEntity, users: List<String>) {
        val seriesId = row.seriesId
        if (row.kind != ItemKind.EPISODE || seriesId == null) return
        val at = now()
        housekeeping.insertReleased(users.map { ReleasedEpisodeEntity(row.serverId, it, row.itemId, seriesId, row.seasonId, at) })
    }

    /** What the prompt may offer [s]. A download [s] may not see answers exactly as no download: nothing to release, no one to count. */
    suspend fun choice(s: Session, itemId: String, signedIn: (String) -> Boolean): ReleaseChoice = db.withTransaction {
        val rule = housekeeping.deleteShared(s.serverId) ?: SharedDelete.ANYONE
        val row = offline.getPlayable(s.serverId, s.userId, itemId) ?: return@withTransaction ReleaseChoice(false, false, rule, 0, false)
        val shared = dao.sharing(s.serverId) == true
        ReleaseChoice(true, shared, rule, if (shared) othersFor(s, itemId, signedIn).size else 0, row.kind == ItemKind.MOVIE)
    }

    /**
     * Other profiles (user ids) that share it, are signed in and were shown it: whom "Watched together" marks. Empty unless [s]
     * may see the download itself and sharing is on.
     */
    suspend fun sharers(s: Session, itemId: String, signedIn: (String) -> Boolean): List<String> = db.withTransaction {
        if (offline.getPlayable(s.serverId, s.userId, itemId) == null) return@withTransaction emptyList()
        othersFor(s, itemId, signedIn)
    }

    private suspend fun othersFor(s: Session, itemId: String, signedIn: (String) -> Boolean): List<String> =
        housekeeping.sharers(s.serverId, s.userId, itemId).filter(signedIn)
}
