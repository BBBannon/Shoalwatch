package app.shoalwatch.data.db.downloads

import androidx.room.withTransaction
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.downloads.ArtworkKey
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

/** What the orphan sweep keeps: every download key, every saved server and every pinned picture, read together. */
class SweepInputs(val known: Set<DownloadKey>, val servers: Set<String>, val artwork: Set<ArtworkKey>) {
    override fun toString() = "SweepInputs(<redacted>)"
}

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
        /** The server confirmed this profile's own CanDownload (true) for the item. */
        val confirmed: Boolean = false,
        /** Planned as "already here": only then may an existing copy gain this profile as an owner. */
        val shareOnly: Boolean = false,
    )

    sealed interface Added {
        data object Queued : Added
        data object OwnerAdded : Added
        data object Skipped : Added
    }

    private val dao = db.downloadDao()
    private val items = db.itemDao()
    private val released = db.housekeepingDao()

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
            // A copy that appeared after a plan for a new download is not shared by the back door: the user plans again, and
            // a share needs this profile's own confirmed permission (Tasks 9-10 review I-2 b).
            if (!new.shareOnly || !new.confirmed) return Added.Skipped
            // Not fetched under this sign-in: the joining owner stays under the strict sharing rule (final review M-1), and the
            // planner's fresh confirmation restarts its permission's age.
            dao.insertOwner(DownloadOwnerEntity(s.serverId, s.userId, new.itemId, at))
            upsertPermission(DownloadKey(s.serverId, new.itemId), s.userId, at)
            released.deleteReleasedFor(s.serverId, s.userId, new.itemId) // this profile has it again (pins unchanged: the row is there)
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
        if (new.confirmed) dao.insertPermission(DownloadPermissionEntity(s.serverId, s.userId, new.itemId, at))
        released.deleteReleased(s.serverId, new.itemId) // a fresh copy: nobody's "watched and deleted" for it any more
        repin(s.serverId, listOfNotNull(new.itemId, new.seasonId, new.seriesId))
        return Added.Queued
    }

    private suspend fun repin(serverId: String, ids: List<String>) = dao.recomputePins(serverId, ids.distinct())

    /**
     * Deletes the row and recomputes its pins. The last download of a series takes every profile's tombstones of that series
     * with it (the show leaves Downloads and normal eviction applies). Watch-and-delete itself (`ReleaseStore`) does not come
     * here: it keeps the tombstones it writes.
     */
    private suspend fun deleteRow(row: DownloadEntity) {
        dao.delete(row.serverId, row.itemId)
        val ids = mutableListOf(row.itemId).apply { addAll(listOfNotNull(row.seasonId, row.seriesId)) }
        if (row.seriesId != null && !released.seriesHasDownload(row.serverId, row.seriesId)) {
            released.releasedOfSeries(row.serverId, row.seriesId).forEach { ids += listOfNotNull(it.itemId, it.seasonId) }
            released.deleteReleasedOfSeries(row.serverId, row.seriesId)
        }
        repin(row.serverId, ids)
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
     * shown the item and confirmed may download it between [confirmedSince] and [confirmedUntil] (the caller passes a week ago
     * and a few minutes ahead while the server can be reached, else any time: final review M-1 and L-2); otherwise (no such account, or sharing off) it is deleted. An heir
     * did not fetch the copy, so it stays under the strict sharing rule. Returns the deleted ones, whose files must go.
     */
    suspend fun afterAccountGone(serverId: String, confirmedSince: Long = Long.MIN_VALUE, confirmedUntil: Long = Long.MAX_VALUE): List<DownloadKey> = pinning {
        val share = dao.sharing(serverId) == true
        val gone = mutableListOf<DownloadKey>()
        for (row in dao.ownerless(serverId)) {
            val heirs = if (share) dao.accountsShown(serverId, row.itemId, confirmedSince, confirmedUntil) else emptyList()
            if (heirs.isEmpty()) {
                deleteRow(row)
                gone += DownloadKey(serverId, row.itemId)
            } else {
                heirs.forEach { dao.insertOwner(DownloadOwnerEntity(serverId, it, row.itemId, now())) }
            }
        }
        dao.recomputePinned(serverId) // the account's tombstones cascaded with it: what only they pinned is let go
        gone
    }

    /** Deletes the row and its owners, tracks and segments, and recomputes the pins (a failed check, a removed server's leftovers). */
    suspend fun delete(key: DownloadKey) = pinning { dao.get(key.serverId, key.itemId)?.let { deleteRow(it) }; Unit }

    /** The QUEUED download with the lowest queue position, on any server. */
    suspend fun next(): DownloadEntity? = dao.next()

    /** RUNNING and VERIFYING back to QUEUED (process death, reboot). */
    suspend fun requeueInterrupted(): Int = dao.requeueInterrupted()

    /**
     * Read, change and write back in one transaction; a throw in [change] leaves the row as it was. The key cannot change, nor
     * can the kind, series or season: the pins follow those, and only [add] and the deletes recompute pins (a moved row would
     * leave the old season pinned for ever and the new one evictable). False when there is no row (it was deleted).
     */
    suspend fun update(key: DownloadKey, change: (DownloadEntity) -> DownloadEntity): Boolean = db.withTransaction {
        val row = dao.get(key.serverId, key.itemId) ?: return@withTransaction false
        val changed = change(row)
        require(changed.serverId == row.serverId && changed.itemId == row.itemId) { "A download's key cannot change." }
        require(changed.kind == row.kind && changed.seriesId == row.seriesId && changed.seasonId == row.seasonId) { "A download's item cannot change." }
        dao.update(changed)
        true
    }

    /** What a conditional write found: written, the row in another state (paused, say), or no row. */
    enum class Written { DONE, MOVED, GONE }

    /**
     * [update] only while the row's state is one of [expected], in one transaction, so a pause or a removal made meanwhile is
     * never overwritten (Tasks 9-10 review I-1).
     */
    suspend fun updateIf(key: DownloadKey, expected: Set<DownloadState>, change: (DownloadEntity) -> DownloadEntity): Written = db.withTransaction {
        val row = dao.get(key.serverId, key.itemId) ?: return@withTransaction Written.GONE
        if (row.state !in expected) return@withTransaction Written.MOVED
        update(key, change)
        Written.DONE
    }

    /**
     * The server confirmed [userId] may download the item (its CanDownload was true, or `/Download` served it to that sign-in).
     * An upsert: a confirmation again moves `confirmedAt` to now, which is the age `reconfirm` reads.
     */
    suspend fun confirm(key: DownloadKey, userId: String) = db.withTransaction { upsertPermission(key, userId, now()) }

    private suspend fun upsertPermission(key: DownloadKey, userId: String, at: Long) {
        if (dao.insertPermission(DownloadPermissionEntity(key.serverId, userId, key.itemId, at)) == -1L) dao.touchPermission(key.serverId, userId, key.itemId, at)
    }

    /** The server said [userId] may not (or did not say): no shared copy, no inheritance. */
    suspend fun unconfirm(key: DownloadKey, userId: String) { dao.deletePermission(key.serverId, userId, key.itemId) }

    /**
     * The server explicitly said [s] may not download [itemId]: its permission goes and, if it owns the copy without its own
     * sign-in having fetched it (inherited, or joined as "already on this phone"), its ownership too (final review M-1). Returns
     * the key when that removed the last owner (the row and pins are gone; the caller deletes the files), else null.
     */
    suspend fun refused(s: Session, itemId: String): DownloadKey? = pinning {
        dao.deletePermission(s.serverId, s.userId, itemId)
        if (!dao.isOwner(s.serverId, s.userId, itemId) || dao.isFetcher(s.serverId, s.userId, itemId)) return@pinning null
        dao.deleteOwner(s.serverId, s.userId, itemId)
        if (dao.owners(s.serverId, itemId).isNotEmpty()) return@pinning null
        dao.get(s.serverId, itemId)?.let { deleteRow(it) }
        DownloadKey(s.serverId, itemId)
    }

    suspend fun isConfirmed(key: DownloadKey, userId: String): Boolean = dao.hasPermission(key.serverId, userId, key.itemId)

    /** [userId]'s own sign-in fetched the copy now on the phone (final review M-1). */
    suspend fun isFetcher(key: DownloadKey, userId: String): Boolean = dao.isFetcher(key.serverId, userId, key.itemId)

    /** [userId]'s sign-in fetched the bytes on the phone: it is the fetcher, and no other owner is any more. */
    suspend fun markFetcher(key: DownloadKey, userId: String) { dao.markFetcher(key.serverId, userId, key.itemId) }

    /**
     * Before a copy fetched by [fetcher] is marked COMPLETE: every other owner whose own permission was never confirmed stops
     * owning it (Tasks 9-10 review I-2 a), so nobody keeps a copy fetched under another profile's permission. Pins are unchanged
     * (the row stays). Returns how many were dropped.
     */
    suspend fun dropUnconfirmedOwners(key: DownloadKey, fetcher: String): Int = db.withTransaction {
        dao.unconfirmedOwners(key.serverId, key.itemId).filter { it != fetcher }.sumOf { dao.deleteOwner(key.serverId, it, key.itemId) }
    }

    /**
     * [userId]'s own fetch was refused (a 403 on `/Download`): its permission goes at once, last owner or not (Tasks 11-12 review
     * I-1), and it stops owning the copy (another owner may fetch it). False if it was the last owner (it stays, FAILED).
     */
    suspend fun dropRefusedOwner(key: DownloadKey, userId: String): Boolean = db.withTransaction {
        dao.deletePermission(key.serverId, userId, key.itemId)
        if (dao.owners(key.serverId, key.itemId).size <= 1) return@withTransaction false
        dao.deleteOwner(key.serverId, userId, key.itemId)
        true
    }

    /** The queue takes a QUEUED row: true only if it was still QUEUED. */
    suspend fun claim(key: DownloadKey): Boolean = dao.claim(key.serverId, key.itemId) == 1

    /** The row, or null (deleted: cancelled, or its account or server removed). */
    suspend fun get(key: DownloadKey): DownloadEntity? = dao.get(key.serverId, key.itemId)

    /** How many downloads wait in the queue (QUEUED), on every server. */
    suspend fun queuedCount(): Int = dao.queuedCount()

    /** Downloads waiting or interrupted (QUEUED, RUNNING, VERIFYING), on every server. */
    suspend fun waitingCount(): Int = dao.waitingCount()

    /** The items [s] owns on its server. */
    suspend fun owned(s: Session): List<String> = dao.ownedItems(s.serverId, s.userId)

    /**
     * Up to [limit] downloads [s] holds under the strict sharing rule whose visibility or permission was last confirmed before
     * [cutoff], or whose permission is dated after [ceiling] (the clock was set back), [first] before the rest: (item, series).
     */
    suspend fun unconfirmedShared(s: Session, cutoff: Long, ceiling: Long, limit: Int, first: String? = null): List<Pair<String, String?>> =
        dao.unconfirmedShared(s.serverId, s.userId, cutoff, ceiling, limit, first).map { it.itemId to it.seriesId }

    /** Pinned pictures no download pins any more (an unknown slot is left out). */
    suspend fun unpinnedArtwork(): List<ArtworkKey> = Converters().let { c ->
        dao.unpinnedArtworkRows().mapNotNull { r -> c.toSlot(r.slot)?.let { ArtworkKey(r.serverId, r.itemId, it, r.tag) } }
    }

    suspend fun unpinArtwork(art: ArtworkKey) = dao.unpinArtwork(art.serverId, art.itemId, art.slot)

    /** The store's clock (epoch ms), for the times the queue writes. */
    fun stamp(): Long = now()

    /** Owners only: QUEUED or RUNNING to PAUSED, or PAUSED back to QUEUED. False when not an owner or nothing changed. */
    suspend fun setPaused(s: Session, itemId: String, paused: Boolean): Boolean = ownerChange(s, itemId) { row ->
        when {
            paused && (row.state == DownloadState.QUEUED || row.state == DownloadState.RUNNING) -> row.copy(state = DownloadState.PAUSED)
            !paused && row.state == DownloadState.PAUSED -> row.copy(state = DownloadState.QUEUED)
            else -> null
        }
    }

    /** Owners only: FAILED back to QUEUED, with no failure, its attempts reset and no failed check remembered. */
    suspend fun retry(s: Session, itemId: String): Boolean = ownerChange(s, itemId) { row ->
        if (row.state == DownloadState.FAILED) row.copy(state = DownloadState.QUEUED, failure = null, attempts = 0, verifiedAt = null) else null
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

    /** Writes only the sharing column, so "Delete shared downloads" in the same row is kept (Tasks 1-2 review M1). */
    suspend fun setSharing(serverId: String, on: Boolean) = db.withTransaction {
        dao.insertSettingsIfMissing(serverId)
        dao.updateSharing(serverId, on)
        Unit
    }

    fun observeSharing(serverId: String): Flow<Boolean> = dao.observeSharing(serverId).map { it == true }.distinctUntilChanged()

    /**
     * The orphan sweep's inputs, read in one transaction; null while any download is RUNNING or VERIFYING (its files are being
     * written or checked, and its `.part` names must not race a sweep). A pinned picture whose slot this version does not know
     * is left out (read as text, mapped through [Converters]). The caller must still hold the queue's lock while it sweeps, so
     * nothing starts between this read and the sweep (Task 12).
     */
    suspend fun sweepInputs(): SweepInputs? = db.withTransaction {
        if (dao.busy()) return@withTransaction null
        val converters = Converters()
        SweepInputs(
            known = dao.keys().map { DownloadKey(it.serverId, it.itemId) }.toSet(),
            servers = dao.serverIds().toSet(),
            artwork = dao.artworkRows().mapNotNull { r -> converters.toSlot(r.slot)?.let { ArtworkKey(r.serverId, r.itemId, it, r.tag) } }.toSet(),
        )
    }

    private companion object {
        val VOLUME_ID = Regex("[A-Za-z0-9-]{1,36}")
    }
}
