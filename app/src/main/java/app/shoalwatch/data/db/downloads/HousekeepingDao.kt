package app.shoalwatch.data.db.downloads

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.MINE
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.MINE_OR_SHARED
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.SEASON_VISIBLE
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.SERIES_VISIBLE
import app.shoalwatch.data.db.downloads.OfflineLibraryDao.Companion.VISIBLE_JOIN
import app.shoalwatch.data.db.library.ItemEntity
import app.shoalwatch.data.db.library.ROW_COLUMNS
import app.shoalwatch.data.db.library.UserDataEntity
import app.shoalwatch.data.downloads.AfterFinish
import app.shoalwatch.data.downloads.SharedDelete
import app.shoalwatch.data.downloads.StorageKind
import kotlinx.coroutines.flow.Flow

/** One series' downloads this profile may see (Storage): the series row and its user data, then counts and sizes. */
class SeriesUseRow(
    @Embedded val item: ItemEntity,
    @Embedded(prefix = "ud_") val userData: UserDataEntity?,
    val episodes: Int,
    val watched: Int,
    val bytes: Long,
    /** This profile owns them and no other profile does: deleting its own downloads frees these. */
    val deletableBytes: Long,
    val ownedEpisodes: Int,
)

/** One film download this profile may see (Storage). */
class FilmUseRow(@Embedded val item: ItemEntity, @Embedded(prefix = "ud_") val userData: UserDataEntity?, val bytes: Long, val ownedByMe: Boolean)

/** Every download's bytes on one volume (all servers and profiles: one number per volume). */
class VolumeBytesRow(val storage: StorageKind, val volumeId: String?, val bytes: Long)

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

    // ---- Storage (SAFEGUARDING: titles, counts and sizes only for downloads this profile may see; the rest is one number)

    @Query(SERIES_USE_SQL)
    internal abstract fun observeSeriesUse(serverId: String, userId: String): Flow<List<SeriesUseRow>>

    @Query(FILMS_USE_SQL)
    internal abstract fun observeFilmUse(serverId: String, userId: String): Flow<List<FilmUseRow>>

    /** Every download of the server, any profile: the caller takes away what this profile may see. */
    @Query("SELECT COALESCE(SUM($BYTES), 0) FROM downloads d WHERE d.serverId = :serverId")
    internal abstract fun observeServerBytes(serverId: String): Flow<Long>

    @Query("SELECT d.storage AS storage, d.volumeId AS volumeId, COALESCE(SUM($BYTES), 0) AS bytes FROM downloads d GROUP BY d.storage, d.volumeId")
    internal abstract fun observeVolumeBytes(): Flow<List<VolumeBytesRow>>

    /** What "Delete watched" may release for this profile: a download it may play (`PLAYABLE_SQL`) that it has watched. */
    @Query(
        "SELECT d.itemId FROM downloads d JOIN items i ON i.serverId = d.serverId AND i.itemId = d.itemId $VISIBLE_JOIN" +
            "JOIN user_data u ON u.serverId = d.serverId AND u.userId = :userId AND u.itemId = d.itemId AND u.played = 1 " +
            "WHERE d.serverId = :serverId AND d.state = 'COMPLETE' AND $MINE_OR_SHARED ORDER BY d.itemId",
    )
    internal abstract suspend fun watchedPlayable(serverId: String, userId: String): List<String>

    /** This profile's OWN downloads of a series it may see (what "Delete downloads of …" removes). */
    @Query(
        "SELECT d.itemId FROM downloads d $VISIBLE_JOIN WHERE d.serverId = :serverId AND d.seriesId = :seriesId AND d.kind = 'EPISODE' " +
            "AND $MINE_OR_SHARED AND $MINE AND $SERIES_VISIBLE AND $SEASON_VISIBLE ORDER BY d.itemId",
    )
    internal abstract suspend fun ownSeriesDownloads(serverId: String, userId: String, seriesId: String): List<String>

    companion object {
        /** A download's size: the whole file once COMPLETE (else what is done), what is done so far otherwise. */
        const val BYTES = "(CASE WHEN d.state = 'COMPLETE' THEN COALESCE(d.totalBytes, d.doneBytes) ELSE d.doneBytes END)"

        /** No other profile owns it. */
        const val ONLY_MINE = "NOT EXISTS (SELECT 1 FROM download_owners oo WHERE oo.serverId = d.serverId AND oo.itemId = d.itemId AND oo.userId <> :userId)"

        /** `i` is the series, `e` the episode; the episode's own data is `ue`. Any state: Storage counts the queue too. */
        const val SERIES_USE_SQL =
            "SELECT $ROW_COLUMNS, COUNT(*) AS episodes, SUM(CASE WHEN ue.played = 1 THEN 1 ELSE 0 END) AS watched, SUM($BYTES) AS bytes, " +
                "SUM(CASE WHEN $MINE AND $ONLY_MINE THEN $BYTES ELSE 0 END) AS deletableBytes, SUM(CASE WHEN $MINE THEN 1 ELSE 0 END) AS ownedEpisodes " +
                "FROM downloads d JOIN items e ON e.serverId = d.serverId AND e.itemId = d.itemId " + VISIBLE_JOIN +
                "JOIN items i ON i.serverId = d.serverId AND i.itemId = d.seriesId " +
                "LEFT JOIN user_data u ON u.serverId = d.serverId AND u.userId = :userId AND u.itemId = d.seriesId " +
                "LEFT JOIN user_data ue ON ue.serverId = d.serverId AND ue.userId = :userId AND ue.itemId = d.itemId " +
                "WHERE d.serverId = :serverId AND d.kind = 'EPISODE' AND $MINE_OR_SHARED AND $SERIES_VISIBLE AND $SEASON_VISIBLE " +
                "GROUP BY i.serverId, i.itemId ORDER BY i.nameKey, i.itemId"

        const val FILMS_USE_SQL =
            "SELECT $ROW_COLUMNS, $BYTES AS bytes, $MINE AS ownedByMe FROM downloads d JOIN items i ON i.serverId = d.serverId AND i.itemId = d.itemId " +
                VISIBLE_JOIN + "LEFT JOIN user_data u ON u.serverId = d.serverId AND u.userId = :userId AND u.itemId = d.itemId " +
                "WHERE d.serverId = :serverId AND d.kind = 'MOVIE' AND $MINE_OR_SHARED ORDER BY i.nameKey, i.itemId"

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
