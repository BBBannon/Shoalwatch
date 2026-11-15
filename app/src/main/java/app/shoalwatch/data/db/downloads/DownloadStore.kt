package app.shoalwatch.data.db.downloads

import androidx.room.withTransaction
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** A download's key. Never printed: ids in a log line could say what a profile downloads. */
data class DownloadKey(val serverId: String, val itemId: String) { override fun toString() = "DownloadKey(<redacted>)" }

sealed interface RemoveOutcome {
    data object KeptForOthers : RemoveOutcome
    data class Deleted(val key: DownloadKey) : RemoveOutcome
    data object NotYours : RemoveOutcome
}

/**
 * The library write lock ([app.shoalwatch.data.library.LibraryRepository.writeGated]): every write that changes
 * `items.pinned` runs under it, so a pin never interleaves with a page write or eviction. [None] is for tests and tools.
 */
interface LibraryWriteLock {
    suspend fun <T> locked(block: suspend () -> T): T

    object None : LibraryWriteLock { override suspend fun <T> locked(block: suspend () -> T): T = block() }
}

/**
 * The download queue rows, their owners and the pins, in Room. The ONE writer of `items.pinned`: every add and delete
 * recomputes the pins of the item, its season and its series in the same transaction, under [lock]. [now] is epoch ms.
 */
internal class DownloadStore(private val db: AppDatabase, private val now: () -> Long, private val lock: LibraryWriteLock = LibraryWriteLock.None) {
    class NewDownload(
        val itemId: String, val kind: ItemKind, val seriesId: String?, val seasonId: String?, val quality: DownloadQuality,
        val storage: StorageKind, val volumeId: String?, val runtimeMs: Long?, val audioIndex: Int?,
    )

    sealed interface Added {
        data object Queued : Added
        data object OwnerAdded : Added
        data object Skipped : Added
    }

    private val dao = db.downloadDao()
    private val items = db.itemDao()

    private suspend fun <T> pinning(block: suspend () -> T): T = lock.locked { db.withTransaction { block() } }

    /**
     * One transaction. The item must exist, be visible to [s], be a film or an episode, and match the stored row's kind,
     * season and series (a caller working from stale data is Skipped, with nothing written). An existing download only gains
     * [s] as an owner. A new one is QUEUED at the end of the queue, in call order, and its item, season and series are pinned.
     * A repeated item id in [downloads] keeps its first result.
     */
    suspend fun add(s: Session, downloads: List<NewDownload>): Map<String, Added> {
        downloads.forEach(::validate)
        return pinning {
            val result = LinkedHashMap<String, Added>()
            for (new in downloads) {
                if (new.itemId in result) continue
                result[new.itemId] = addOne(s, new)
            }
            result
        }
    }

    /** Programmer errors (fixed messages, never an id): the storage and its volume id must agree. */
    private fun validate(new: NewDownload) {
        when (new.storage) {
            StorageKind.INTERNAL -> require(new.volumeId == null) { "Internal storage has no volume id." }
            StorageKind.REMOVABLE -> require(new.volumeId != null && VOLUME_ID.matches(new.volumeId)) { "A removable volume needs a valid volume id." }
        }
    }

    private suspend fun addOne(s: Session, new: NewDownload): Added {
        if (new.kind != ItemKind.MOVIE && new.kind != ItemKind.EPISODE) return Added.Skipped
        val stored = items.getItem(s.serverId, s.userId, new.itemId)?.item ?: return Added.Skipped
        if (stored.kind != new.kind || stored.seriesId != new.seriesId || stored.seasonId != new.seasonId) return Added.Skipped
        val at = now()
        if (dao.get(s.serverId, new.itemId) != null) {
            dao.insertOwner(DownloadOwnerEntity(s.serverId, s.userId, new.itemId, at))
            return Added.OwnerAdded
        }
        dao.insert(
            DownloadEntity(
                serverId = s.serverId, itemId = new.itemId, kind = new.kind, seriesId = new.seriesId, seasonId = new.seasonId,
                quality = new.quality, state = DownloadState.QUEUED, failure = null, storage = new.storage, volumeId = new.volumeId,
                mediaSourceId = null, container = null, totalBytes = null, doneBytes = 0, segmentsTotal = null, segmentsDone = 0,
                validator = null, restarts = 0, attempts = 0, runtimeMs = new.runtimeMs, audioIndex = new.audioIndex, sha256 = null,
                queuePosition = (dao.lastPosition() ?: 0) + 1, requestedAt = at, completedAt = null, verifiedAt = null,
            ),
        )
        dao.insertOwner(DownloadOwnerEntity(s.serverId, s.userId, new.itemId, at))
        repin(s.serverId, listOfNotNull(new.itemId, new.seasonId, new.seriesId))
        return Added.Queued
    }

    private suspend fun repin(serverId: String, ids: List<String>) = dao.recomputePins(serverId, ids.distinct())

    private suspend fun deleteRow(row: DownloadEntity) {
        dao.delete(row.serverId, row.itemId)
        repin(row.serverId, listOfNotNull(row.itemId, row.seasonId, row.seriesId))
    }

    /** Removes [s]'s ownership. With no owner left the row goes too (pins recomputed) and Deleted is returned so the caller removes the files. */
    suspend fun removeOwner(s: Session, itemId: String): RemoveOutcome = pinning {
        if (dao.deleteOwner(s.serverId, s.userId, itemId) == 0) return@pinning RemoveOutcome.NotYours
        if (dao.owners(s.serverId, itemId).isNotEmpty()) return@pinning RemoveOutcome.KeptForOthers
        dao.get(s.serverId, itemId)?.let { deleteRow(it) }
        RemoveOutcome.Deleted(DownloadKey(s.serverId, itemId))
    }

    /**
     * The ownership rule after one of [serverId]'s accounts was removed (its ownership has already cascaded away). Each
     * download left with no owner passes, when sharing is on, to every remaining account on the server that the server has
     * shown the item; otherwise (no such account, or sharing off) it is deleted. Returns the deleted ones, whose files must go.
     */
    suspend fun afterAccountGone(serverId: String): List<DownloadKey> = pinning {
        val share = dao.sharing(serverId) == true
        val gone = mutableListOf<DownloadKey>()
        for (row in dao.ownerless(serverId)) {
            val heirs = if (share) dao.accountsShown(serverId, row.itemId) else emptyList()
            if (heirs.isEmpty()) {
                deleteRow(row)
                gone += DownloadKey(serverId, row.itemId)
            } else {
                heirs.forEach { dao.insertOwner(DownloadOwnerEntity(serverId, it, row.itemId, now())) }
            }
        }
        gone
    }

    /** Deletes the row and its owners, tracks and segments, and recomputes the pins (a failed check, a removed server's leftovers). */
    suspend fun delete(key: DownloadKey) = pinning { dao.get(key.serverId, key.itemId)?.let { deleteRow(it) }; Unit }

    /** The QUEUED download with the lowest queue position, on any server. */
    suspend fun next(): DownloadEntity? = dao.next()

    /** RUNNING and VERIFYING back to QUEUED (process death, reboot). */
    suspend fun requeueInterrupted(): Int = dao.requeueInterrupted()

    /** Read, change and write back in one transaction; a throw in [change] leaves the row as it was. The key cannot change. */
    suspend fun update(key: DownloadKey, change: (DownloadEntity) -> DownloadEntity) = db.withTransaction {
        val row = dao.get(key.serverId, key.itemId) ?: return@withTransaction
        val changed = change(row)
        require(changed.serverId == row.serverId && changed.itemId == row.itemId) { "A download's key cannot change." }
        dao.update(changed)
        Unit
    }

    /** Owners only: QUEUED or RUNNING to PAUSED, or PAUSED back to QUEUED. False when not an owner or nothing changed. */
    suspend fun setPaused(s: Session, itemId: String, paused: Boolean): Boolean = ownerChange(s, itemId) { row ->
        when {
            paused && (row.state == DownloadState.QUEUED || row.state == DownloadState.RUNNING) -> row.copy(state = DownloadState.PAUSED)
            !paused && row.state == DownloadState.PAUSED -> row.copy(state = DownloadState.QUEUED)
            else -> null
        }
    }

    /** Owners only: FAILED back to QUEUED, with no failure and its attempts reset. */
    suspend fun retry(s: Session, itemId: String): Boolean = ownerChange(s, itemId) { row ->
        if (row.state == DownloadState.FAILED) row.copy(state = DownloadState.QUEUED, failure = null, attempts = 0) else null
    }

    private suspend fun ownerChange(s: Session, itemId: String, change: (DownloadEntity) -> DownloadEntity?): Boolean = db.withTransaction {
        if (!dao.isOwner(s.serverId, s.userId, itemId)) return@withTransaction false
        val row = dao.get(s.serverId, itemId) ?: return@withTransaction false
        val changed = change(row) ?: return@withTransaction false
        dao.update(changed) > 0
    }

    suspend fun owners(key: DownloadKey): List<String> = dao.owners(key.serverId, key.itemId)

    /** "Share downloads between profiles" for the server; off when never set. */
    suspend fun sharing(serverId: String): Boolean = dao.sharing(serverId) == true

    suspend fun setSharing(serverId: String, on: Boolean) = dao.upsertSettings(DownloadSettingsEntity(serverId, on))

    fun observeSharing(serverId: String): Flow<Boolean> = dao.observeSharing(serverId).map { it == true }.distinctUntilChanged()

    private companion object {
        val VOLUME_ID = Regex("[A-Za-z0-9-]{1,36}")
    }
}
