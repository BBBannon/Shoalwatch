package app.shoalwatch.data.db.downloads

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import app.shoalwatch.data.downloads.AfterFinish
import app.shoalwatch.data.downloads.SharedDelete
import kotlinx.coroutines.flow.Flow

/**
 * Watch-and-delete's reads and writes: per-profile settings (`profile_download_settings`), the per-server "Delete shared
 * downloads" (`download_settings.deleteShared`), the per-profile tombstones (`released_episodes`) and who shares a download.
 * Every settings write touches its own column only (INSERT OR IGNORE of a default row, then UPDATE), so one setting never
 * resets another. Nothing newer than SQLite 3.22.
 */
@Dao
abstract class HousekeepingDao {
    // ---- per-profile settings

    @Query("SELECT * FROM profile_download_settings WHERE serverId = :serverId AND userId = :userId")
    internal abstract fun observeProfile(serverId: String, userId: String): Flow<ProfileDownloadSettingsEntity?>

    @Query("SELECT * FROM profile_download_settings WHERE serverId = :serverId AND userId = :userId")
    internal abstract suspend fun profile(serverId: String, userId: String): ProfileDownloadSettingsEntity?

    @Query("INSERT OR IGNORE INTO profile_download_settings (serverId, userId) VALUES (:serverId, :userId)")
    internal abstract suspend fun insertProfileIfMissing(serverId: String, userId: String)

    @Query("UPDATE profile_download_settings SET afterFinish = :value WHERE serverId = :serverId AND userId = :userId")
    internal abstract suspend fun updateAfterFinish(serverId: String, userId: String, value: AfterFinish): Int

    @Query("UPDATE profile_download_settings SET autoNextCount = :count WHERE serverId = :serverId AND userId = :userId")
    internal abstract suspend fun updateAutoNext(serverId: String, userId: String, count: Int): Int

    // ---- per-server "Delete shared downloads" (the row is shared with "Share downloads between profiles")

    @Query("SELECT deleteShared FROM download_settings WHERE serverId = :serverId")
    internal abstract fun observeDeleteShared(serverId: String): Flow<SharedDelete?>

    @Query("SELECT deleteShared FROM download_settings WHERE serverId = :serverId")
    internal abstract suspend fun deleteShared(serverId: String): SharedDelete?

    @Query("INSERT OR IGNORE INTO download_settings (serverId) VALUES (:serverId)")
    internal abstract suspend fun insertServerSettingsIfMissing(serverId: String)

    @Query("UPDATE download_settings SET deleteShared = :value WHERE serverId = :serverId")
    internal abstract suspend fun updateDeleteShared(serverId: String, value: SharedDelete): Int

    // ---- tombstones

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    internal abstract suspend fun insertReleased(rows: List<ReleasedEpisodeEntity>)

    /** Every profile's tombstone of the item (a fresh download of it). */
    @Query("DELETE FROM released_episodes WHERE serverId = :serverId AND itemId = :itemId")
    internal abstract suspend fun deleteReleased(serverId: String, itemId: String): Int

    /** One profile's tombstone of the item (it owns the copy again). */
    @Query("DELETE FROM released_episodes WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId")
    internal abstract suspend fun deleteReleasedFor(serverId: String, userId: String, itemId: String): Int

    @Query("SELECT * FROM released_episodes WHERE serverId = :serverId AND seriesId = :seriesId")
    internal abstract suspend fun releasedOfSeries(serverId: String, seriesId: String): List<ReleasedEpisodeEntity>

    @Query("DELETE FROM released_episodes WHERE serverId = :serverId AND seriesId = :seriesId")
    internal abstract suspend fun deleteReleasedOfSeries(serverId: String, seriesId: String): Int

    @Query("SELECT EXISTS (SELECT 1 FROM downloads WHERE serverId = :serverId AND seriesId = :seriesId)")
    internal abstract suspend fun seriesHasDownload(serverId: String, seriesId: String): Boolean

    // ---- the release rule's reads

    /** [userId]'s own watched state of the item on this phone (null: no row). */
    @Query("SELECT played FROM user_data WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId")
    internal abstract suspend fun played(serverId: String, userId: String, itemId: String): Boolean?

    /** The other accounts that see this shared download in their own offline library: what "Watched together" may mark. */
    @Query(SHARERS_SQL)
    internal abstract suspend fun sharers(serverId: String, userId: String, itemId: String): List<String>

    // ---- auto-download next (one request per profile and series, the newest anchor winning)

    /** The download [userId] may play (`OfflineLibraryDao.PLAYABLE_SQL`): what an anchor must be. */
    @Query(OfflineLibraryDao.PLAYABLE_SQL)
    internal abstract suspend fun playable(serverId: String, userId: String, itemId: String): DownloadEntity?

    @Query("SELECT * FROM auto_next_requests WHERE serverId = :serverId AND userId = :userId AND seriesId = :seriesId")
    internal abstract suspend fun autoNextRequest(serverId: String, userId: String, seriesId: String): AutoNextRequestEntity?

    @Query("SELECT * FROM auto_next_requests WHERE serverId = :serverId AND userId = :userId ORDER BY requestedAt, seriesId")
    internal abstract suspend fun autoNextRequests(serverId: String, userId: String): List<AutoNextRequestEntity>

    @Query("SELECT * FROM auto_next_requests ORDER BY serverId, userId, requestedAt, seriesId")
    internal abstract suspend fun allAutoNextRequests(): List<AutoNextRequestEntity>

    @Query("SELECT DISTINCT serverId FROM auto_next_requests")
    internal abstract fun observeAutoNextServers(): Flow<List<String>>

    /** "Next episodes weren't downloaded automatically": a waiting request of this profile last found too little space. */
    @Query("SELECT EXISTS (SELECT 1 FROM auto_next_requests WHERE serverId = :serverId AND userId = :userId AND blockedAt IS NOT NULL)")
    internal abstract fun observeAutoNextBlocked(serverId: String, userId: String): Flow<Boolean>

    @Upsert
    internal abstract suspend fun upsertAutoNext(row: AutoNextRequestEntity)

    /** Only the request as it was read: a newer anchor written meanwhile stays. */
    @Query(
        "DELETE FROM auto_next_requests WHERE serverId = :serverId AND userId = :userId AND seriesId = :seriesId " +
            "AND anchorItemId = :anchorItemId AND requestedAt = :requestedAt",
    )
    internal abstract suspend fun deleteAutoNext(serverId: String, userId: String, seriesId: String, anchorItemId: String, requestedAt: Long): Int

    @Query(
        "UPDATE auto_next_requests SET tries = :tries, blockedAt = :blockedAt WHERE serverId = :serverId AND userId = :userId " +
            "AND seriesId = :seriesId AND anchorItemId = :anchorItemId AND requestedAt = :requestedAt",
    )
    internal abstract suspend fun updateAutoNext(
        serverId: String, userId: String, seriesId: String, anchorItemId: String, requestedAt: Long, tries: Int, blockedAt: Long?,
    ): Int

    companion object {
        /**
         * SAFEGUARDING. Accounts on the SAME server, other than [userId], with their OWN `visible_items` record for the item, while
         * sharing is on and the copy is COMPLETE, that own it (fetched by their sign-in) or whose own permission is confirmed: the
         * shared half of `OfflineLibraryDao.MINE_OR_SHARED`, for each of them. Signed-in is checked by the caller.
         */
        const val SHARERS_SQL =
            "SELECT a.userId FROM accounts a JOIN downloads d ON d.serverId = a.serverId AND d.itemId = :itemId " +
                "JOIN visible_items v ON v.serverId = a.serverId AND v.userId = a.userId AND v.itemId = d.itemId " +
                "WHERE a.serverId = :serverId AND a.userId <> :userId AND d.state = 'COMPLETE' " +
                "AND EXISTS (SELECT 1 FROM download_settings x WHERE x.serverId = d.serverId AND x.shareBetweenProfiles = 1) " +
                "AND (EXISTS (SELECT 1 FROM download_owners o WHERE o.serverId = d.serverId AND o.itemId = d.itemId AND o.userId = a.userId AND o.fetched = 1) " +
                "OR EXISTS (SELECT 1 FROM download_permissions p WHERE p.serverId = d.serverId AND p.itemId = d.itemId AND p.userId = a.userId)) " +
                "ORDER BY a.userId"
    }
}
