package app.shoalwatch.data.db.downloads

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
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
