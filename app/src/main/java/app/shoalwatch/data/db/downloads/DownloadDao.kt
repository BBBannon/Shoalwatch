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

    /** Shared COMPLETE downloads [userId] does not own, whose visibility record is older than [cutoff] (oldest first). */
    @Query(
        "SELECT d.itemId AS itemId, d.seriesId AS seriesId FROM downloads d " +
            "JOIN visible_items v ON v.serverId = d.serverId AND v.userId = :userId AND v.itemId = d.itemId " +
            "WHERE d.serverId = :serverId AND d.state = 'COMPLETE' AND v.seenAt < :cutoff " +
            "AND EXISTS (SELECT 1 FROM download_settings x WHERE x.serverId = d.serverId AND x.shareBetweenProfiles = 1) " +
            "AND NOT EXISTS (SELECT 1 FROM download_owners o WHERE o.serverId = d.serverId AND o.itemId = d.itemId AND o.userId = :userId) " +
            "ORDER BY v.seenAt LIMIT :limit",
    )
    internal abstract suspend fun unconfirmedShared(serverId: String, userId: String, cutoff: Long, limit: Int): List<SharedRow>

    /** Pinned pictures whose item no download pins any more (its row is not pinned, or gone). */
    @Query(
        "SELECT serverId, itemId, slot, tag FROM pinned_artwork p WHERE NOT EXISTS " +
            "(SELECT 1 FROM items i WHERE i.serverId = p.serverId AND i.itemId = p.itemId AND i.pinned = 1)",
    )
    internal abstract suspend fun unpinnedArtworkRows(): List<ArtworkRow>

    @Query("SELECT COUNT(*) FROM downloads WHERE state = 'QUEUED'")
    internal abstract suspend fun queuedCount(): Int

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

    /** The accounts on the server that the server has shown [itemId] (the ownership rule hands a shared download to them). */
    @Query("SELECT a.userId FROM accounts a JOIN visible_items v ON v.serverId = a.serverId AND v.userId = a.userId AND v.itemId = :itemId WHERE a.serverId = :serverId ORDER BY a.userId")
    internal abstract suspend fun accountsShown(serverId: String, itemId: String): List<String>

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
