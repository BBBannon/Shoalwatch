package app.shoalwatch.data.db.downloads

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import app.shoalwatch.data.downloads.ArtworkSlot
import kotlinx.coroutines.flow.Flow

/**
 * The raw reads and writes [DownloadStore] composes into transactions. Nothing here filters by visibility: these are for the
 * store, the queue and tests, never for what a user sees (that is [OfflineLibraryDao]). Inserts are plain `INSERT` or
 * `INSERT OR IGNORE`, never `REPLACE` (it deletes first and would cascade the owners away).
 */
@Dao
abstract class DownloadDao {
    @Query("SELECT * FROM downloads WHERE serverId = :serverId AND itemId = :itemId")
    abstract suspend fun get(serverId: String, itemId: String): DownloadEntity?

    @Insert
    internal abstract suspend fun insert(row: DownloadEntity)

    @Update
    internal abstract suspend fun update(row: DownloadEntity): Int

    @Query("DELETE FROM downloads WHERE serverId = :serverId AND itemId = :itemId")
    internal abstract suspend fun delete(serverId: String, itemId: String): Int

    /** The highest queue position in use on any server (the queue is one line across servers), or null when there is none. */
    @Query("SELECT MAX(queuePosition) FROM downloads")
    internal abstract suspend fun lastPosition(): Long?

    @Query("SELECT COUNT(*) FROM downloads WHERE state = 'COMPLETE' AND completedAt >= :since")
    internal abstract suspend fun completedSince(since: Long): Int

    @Query("SELECT COUNT(*) FROM downloads WHERE state IN ('QUEUED', 'RUNNING', 'VERIFYING')")
    internal abstract suspend fun waitingCount(): Int

    @Query("SELECT itemId FROM download_owners WHERE serverId = :serverId AND userId = :userId ORDER BY addedAt, itemId")
    internal abstract suspend fun ownedItems(serverId: String, userId: String): List<String>

    /**
     * COMPLETE downloads [userId] was shown and holds under the strict sharing rule (shared to it with sharing on, or owned by it
     * without its own sign-in having fetched them: final review M-1), whose visibility record is older than [cutoff], or whose
     * download permission is missing or was confirmed before [cutoff] (its own age, read from `confirmedAt`, whatever browsing
     * keeps the visibility record fresh: Tasks 11-12 review I-1), or dated after [ceiling] (the clock was set back: final review
     * L-2). [first] (the item about to play: final review L-11), then the oldest confirmation first: what `reconfirm` asks about.
     */
    @Query(
        "SELECT d.itemId AS itemId, d.seriesId AS seriesId FROM downloads d " +
            "JOIN visible_items v ON v.serverId = d.serverId AND v.userId = :userId AND v.itemId = d.itemId " +
            "LEFT JOIN download_permissions p ON p.serverId = d.serverId AND p.userId = :userId AND p.itemId = d.itemId " +
            "WHERE d.serverId = :serverId AND d.state = 'COMPLETE' AND (v.seenAt < :cutoff OR p.confirmedAt IS NULL OR p.confirmedAt < :cutoff OR p.confirmedAt > :ceiling) " +
            "AND NOT EXISTS (SELECT 1 FROM download_owners o WHERE o.serverId = d.serverId AND o.itemId = d.itemId AND o.userId = :userId AND o.fetched = 1) " +
            "AND (EXISTS (SELECT 1 FROM download_owners o WHERE o.serverId = d.serverId AND o.itemId = d.itemId AND o.userId = :userId) " +
            "OR EXISTS (SELECT 1 FROM download_settings x WHERE x.serverId = d.serverId AND x.shareBetweenProfiles = 1)) " +
            "ORDER BY CASE WHEN d.itemId = :first THEN 0 ELSE 1 END, COALESCE(p.confirmedAt, 0), v.seenAt LIMIT :limit",
    )
    internal abstract suspend fun unconfirmedShared(serverId: String, userId: String, cutoff: Long, ceiling: Long, limit: Int, first: String?): List<SharedRow>

    /** Pinned pictures whose item no download pins any more (its row is not pinned, or gone). */
    @Query(
        "SELECT serverId, itemId, slot, tag FROM pinned_artwork p WHERE NOT EXISTS " +
            "(SELECT 1 FROM items i WHERE i.serverId = p.serverId AND i.itemId = p.itemId AND i.pinned = 1)",
    )
    internal abstract suspend fun unpinnedArtworkRows(): List<ArtworkRow>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    internal abstract suspend fun insertPermission(row: DownloadPermissionEntity): Long

    @Query("UPDATE download_permissions SET confirmedAt = :nowMs WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId")
    internal abstract suspend fun touchPermission(serverId: String, userId: String, itemId: String, nowMs: Long): Int

    @Query("DELETE FROM download_permissions WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId")
    internal abstract suspend fun deletePermission(serverId: String, userId: String, itemId: String): Int

    @Query("SELECT EXISTS (SELECT 1 FROM download_permissions WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId)")
    internal abstract suspend fun hasPermission(serverId: String, userId: String, itemId: String): Boolean

    /** Owners of the download with no confirmed permission. */
    @Query(
        "SELECT o.userId FROM download_owners o WHERE o.serverId = :serverId AND o.itemId = :itemId AND NOT EXISTS " +
            "(SELECT 1 FROM download_permissions p WHERE p.serverId = o.serverId AND p.userId = o.userId AND p.itemId = o.itemId)",
    )
    internal abstract suspend fun unconfirmedOwners(serverId: String, itemId: String): List<String>

    @Query("SELECT COUNT(*) FROM downloads WHERE state = 'QUEUED'")
    internal abstract suspend fun queuedCount(): Int

    /** QUEUED to RUNNING only if it is still QUEUED (a pause or a removal in between wins): 1, else 0. */
    @Query("UPDATE downloads SET state = 'RUNNING' WHERE serverId = :serverId AND itemId = :itemId AND state = 'QUEUED'")
    internal abstract suspend fun claim(serverId: String, itemId: String): Int

    @Query("SELECT * FROM downloads WHERE state = 'QUEUED' ORDER BY queuePosition LIMIT 1")
    internal abstract suspend fun next(): DownloadEntity?

    @Query("UPDATE downloads SET state = 'QUEUED' WHERE state IN ('RUNNING', 'VERIFYING')")
    internal abstract suspend fun requeueInterrupted(): Int

    @Query("SELECT * FROM downloads WHERE serverId = :serverId AND NOT EXISTS (SELECT 1 FROM download_owners o WHERE o.serverId = downloads.serverId AND o.itemId = downloads.itemId)")
    internal abstract suspend fun ownerless(serverId: String): List<DownloadEntity>

    // ---- the orphan sweep's inputs

    @Query("SELECT EXISTS (SELECT 1 FROM downloads WHERE state IN ('RUNNING', 'VERIFYING'))")
    internal abstract suspend fun busy(): Boolean

    @Query("SELECT serverId, itemId FROM downloads")
    internal abstract suspend fun keys(): List<KeyRow>

    @Query("SELECT id FROM servers")
    internal abstract suspend fun serverIds(): List<String>

    /** [ArtworkRow.slot] is read as text: an unknown slot would fail Room's not-null check in a whole entity. */
    @Query("SELECT serverId, itemId, slot, tag FROM pinned_artwork")
    internal abstract suspend fun artworkRows(): List<ArtworkRow>

    // ---- owners

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    internal abstract suspend fun insertOwner(row: DownloadOwnerEntity): Long

    @Query("DELETE FROM download_owners WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId")
    internal abstract suspend fun deleteOwner(serverId: String, userId: String, itemId: String): Int

    @Query("SELECT userId FROM download_owners WHERE serverId = :serverId AND itemId = :itemId ORDER BY addedAt, userId")
    internal abstract suspend fun owners(serverId: String, itemId: String): List<String>

    @Query("SELECT EXISTS (SELECT 1 FROM download_owners WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId)")
    internal abstract suspend fun isOwner(serverId: String, userId: String, itemId: String): Boolean

    /** [userId]'s own sign-in fetched the copy (final review M-1). */
    @Query("SELECT EXISTS (SELECT 1 FROM download_owners WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId AND fetched = 1)")
    internal abstract suspend fun isFetcher(serverId: String, userId: String, itemId: String): Boolean

    /** [userId] is the one owner whose sign-in fetched the copy; every other owner's mark goes. */
    @Query("UPDATE download_owners SET fetched = CASE WHEN userId = :userId THEN 1 ELSE 0 END WHERE serverId = :serverId AND itemId = :itemId")
    internal abstract suspend fun markFetcher(serverId: String, userId: String, itemId: String): Int

    /**
     * The accounts on the server that the server has shown [itemId] AND confirmed may download it between [confirmedSince] and
     * [confirmedUntil] (the ownership rule hands a shared download only to them: the strict sharing rule; final review M-1, L-2).
     */
    @Query(
        "SELECT a.userId FROM accounts a JOIN visible_items v ON v.serverId = a.serverId AND v.userId = a.userId AND v.itemId = :itemId " +
            "JOIN download_permissions p ON p.serverId = a.serverId AND p.userId = a.userId AND p.itemId = :itemId " +
            "WHERE a.serverId = :serverId AND p.confirmedAt >= :confirmedSince AND p.confirmedAt <= :confirmedUntil ORDER BY a.userId",
    )
    internal abstract suspend fun accountsShown(serverId: String, itemId: String, confirmedSince: Long, confirmedUntil: Long): List<String>

    // ---- pins: `items.pinned` has one writer, this statement

    /** Recomputes `pinned` for [ids]: set while any download on the server is that item, or in that season or series. */
    @Query(
        "UPDATE items SET pinned = CASE WHEN EXISTS (SELECT 1 FROM downloads d WHERE d.serverId = items.serverId " +
            "AND (d.itemId = items.itemId OR d.seasonId = items.itemId OR d.seriesId = items.itemId)) THEN 1 ELSE 0 END " +
            "WHERE serverId = :serverId AND itemId IN (:ids)",
    )
    internal abstract suspend fun recomputePins(serverId: String, ids: List<String>)

    // ---- extras: tracks, skip segments, pinned artwork

    @Query("DELETE FROM download_tracks WHERE serverId = :serverId AND itemId = :itemId")
    internal abstract suspend fun deleteTracks(serverId: String, itemId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    internal abstract suspend fun insertTracks(rows: List<DownloadTrackEntity>)

    /** The download's track list, replaced as a whole (DELETE then INSERT in one transaction; never REPLACE). */
    @Transaction
    internal open suspend fun replaceTracks(serverId: String, itemId: String, rows: List<DownloadTrackEntity>) {
        deleteTracks(serverId, itemId)
        insertTracks(rows)
    }

    /** Kinds as stored text: an unknown one would fail Room's not-null check in a whole entity (see [Converters]). */
    @Query("SELECT * FROM download_tracks WHERE serverId = :serverId AND itemId = :itemId ORDER BY type, streamIndex")
    internal abstract suspend fun trackRows(serverId: String, itemId: String): List<TrackRow>

    /** The saved track list; a row whose type or subtitle kind this version does not know is left out. */
    internal suspend fun tracks(serverId: String, itemId: String): List<DownloadTrackEntity> = Converters().let { c -> trackRows(serverId, itemId).mapNotNull { it.toEntity(c) } }

    @Query("DELETE FROM download_segments WHERE serverId = :serverId AND itemId = :itemId")
    internal abstract suspend fun deleteSegments(serverId: String, itemId: String)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    internal abstract suspend fun insertSegments(rows: List<DownloadSegmentEntity>)

    @Transaction
    internal open suspend fun replaceSegments(serverId: String, itemId: String, rows: List<DownloadSegmentEntity>) {
        deleteSegments(serverId, itemId)
        insertSegments(rows)
    }

    @Query("SELECT * FROM download_segments WHERE serverId = :serverId AND itemId = :itemId ORDER BY startMs, kind")
    internal abstract suspend fun segmentRows(serverId: String, itemId: String): List<SegmentRow>

    /** The saved skip markers in start order; a kind this version does not know is left out. */
    internal suspend fun segments(serverId: String, itemId: String): List<DownloadSegmentEntity> =
        Converters().let { c -> segmentRows(serverId, itemId).mapNotNull { r -> c.toSegmentKind(r.kind)?.let { DownloadSegmentEntity(r.serverId, r.itemId, it, r.startMs, r.endMs) } } }

    @Query("SELECT serverId, itemId, slot, tag FROM pinned_artwork WHERE serverId = :serverId AND itemId = :itemId")
    internal abstract suspend fun artworkRowsFor(serverId: String, itemId: String): List<ArtworkRow>

    /** The pinned picture tags of an item by slot; an unknown slot is left out. */
    internal suspend fun pinnedArtwork(serverId: String, itemId: String): Map<ArtworkSlot, String> {
        val converters = Converters()
        return artworkRowsFor(serverId, itemId).mapNotNull { r -> converters.toSlot(r.slot)?.let { it to r.tag } }.toMap()
    }

    @Query("INSERT OR IGNORE INTO pinned_artwork (serverId, itemId, slot, tag, bytes, savedAt) VALUES (:serverId, :itemId, :slot, :tag, :bytes, :savedAt)")
    internal abstract suspend fun insertArtwork(serverId: String, itemId: String, slot: String, tag: String, bytes: Long, savedAt: Long)

    @Query("UPDATE pinned_artwork SET tag = :tag, bytes = :bytes, savedAt = :savedAt WHERE serverId = :serverId AND itemId = :itemId AND slot = :slot")
    internal abstract suspend fun updateArtwork(serverId: String, itemId: String, slot: String, tag: String, bytes: Long, savedAt: Long)

    /** INSERT OR IGNORE, then UPDATE (never REPLACE). */
    @Transaction
    internal open suspend fun pinArtwork(serverId: String, itemId: String, slot: ArtworkSlot, tag: String, bytes: Long, savedAt: Long) {
        insertArtwork(serverId, itemId, slot.name, tag, bytes, savedAt)
        updateArtwork(serverId, itemId, slot.name, tag, bytes, savedAt)
    }

    @Query("DELETE FROM pinned_artwork WHERE serverId = :serverId AND itemId = :itemId AND slot = :slot")
    internal abstract suspend fun deleteArtwork(serverId: String, itemId: String, slot: String)

    internal suspend fun unpinArtwork(serverId: String, itemId: String, slot: ArtworkSlot) = deleteArtwork(serverId, itemId, slot.name)

    // ---- per-server settings

    @Query("SELECT shareBetweenProfiles FROM download_settings WHERE serverId = :serverId")
    internal abstract suspend fun sharing(serverId: String): Boolean?

    @Query("SELECT shareBetweenProfiles FROM download_settings WHERE serverId = :serverId")
    internal abstract fun observeSharing(serverId: String): Flow<Boolean?>

    @Upsert
    internal abstract suspend fun upsertSettings(row: DownloadSettingsEntity)
}

internal data class KeyRow(val serverId: String, val itemId: String)

/** A `pinned_artwork` row with its slot as stored text (mapped through `Converters.toSlot`; unknown is dropped). */
internal data class ArtworkRow(val serverId: String, val itemId: String, val slot: String, val tag: String) {
    override fun toString() = "ArtworkRow(<redacted>)"
}

/** A `download_tracks` row with its type and subtitle kind as stored text. */
internal data class TrackRow(
    val serverId: String, val itemId: String, val type: String, val streamIndex: Int, val label: String, val language: String?,
    val codec: String?, val kind: String?, val fileFormat: String?, val forced: Boolean, val hearingImpaired: Boolean,
    val isDefault: Boolean, val external: Boolean, val channels: Int?, val fileSaved: Boolean,
) {
    override fun toString() = "TrackRow(<redacted>)"

    /** Null when the type, or a subtitle kind that is present, is one this version does not know. */
    fun toEntity(c: Converters): DownloadTrackEntity? {
        val t = c.toTrackType(type) ?: return null
        val k = kind?.let { c.toSubtitleKind(it) ?: return null }
        return DownloadTrackEntity(serverId, itemId, t, streamIndex, label, language, codec, k, fileFormat, forced, hearingImpaired, isDefault, external, channels, fileSaved)
    }
}

/** A `download_segments` row with its kind as stored text. */
internal data class SegmentRow(val serverId: String, val itemId: String, val kind: String, val startMs: Long, val endMs: Long) {
    override fun toString() = "SegmentRow(<redacted>)"
}

/** A shared download's item and series, for reconfirming visibility. Never printed. */
class SharedRow(val itemId: String, val seriesId: String?) { override fun toString() = "SharedRow(<redacted>)" }
