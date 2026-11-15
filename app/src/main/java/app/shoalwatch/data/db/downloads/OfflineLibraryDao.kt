package app.shoalwatch.data.db.downloads

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Query
import app.shoalwatch.data.db.library.ItemEntity
import app.shoalwatch.data.db.library.ItemRow
import app.shoalwatch.data.db.library.ROW_COLUMNS
import app.shoalwatch.data.db.library.UserDataEntity
import app.shoalwatch.data.db.library.toModel
import app.shoalwatch.data.downloads.DownloadInfo
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.downloads.OfflineEntry
import app.shoalwatch.data.downloads.SeriesSummary
import app.shoalwatch.data.library.ItemWithUserData
import kotlinx.coroutines.flow.Flow

/** A downloaded item joined to the session user's own data, its download row, and whether that user owns it. */
class OfflineRow(
    @Embedded val item: ItemEntity,
    @Embedded(prefix = "ud_") val userData: UserDataEntity?,
    @Embedded(prefix = "d_") val download: DownloadEntity,
    val ownedByMe: Boolean,
)

/** The model of this row. [available] is false when the copy is on an SD card that is not in the phone now (the caller knows the volumes). */
fun OfflineRow.toEntry(available: Boolean) = OfflineEntry(
    item = ItemWithUserData(item.toModel(), userData?.toModel()),
    download = DownloadInfo(
        itemId = download.itemId, state = download.state, failure = download.failure, quality = download.quality,
        progress = download.progress(), totalBytes = download.totalBytes, ownedByMe = ownedByMe, storage = download.storage, available = available,
    ),
)

/** 1 when COMPLETE; else bytes done of the total, or segments done for a conversion; null when neither total is known. */
internal fun DownloadEntity.progress(): Float? = when {
    state == DownloadState.COMPLETE -> 1f
    segmentsTotal != null && segmentsTotal > 0 -> (segmentsDone.toFloat() / segmentsTotal).coerceIn(0f, 1f)
    totalBytes != null && totalBytes > 0 -> (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f)
    else -> null
}

fun SeriesSummaryRow.toModel() = SeriesSummary(ItemWithUserData(item.toModel(), userData?.toModel()), episodeCount, bytes)

/** A series with at least one ready episode this user may see, how many, and their size in bytes. */
class SeriesSummaryRow(
    @Embedded val item: ItemEntity,
    @Embedded(prefix = "ud_") val userData: UserDataEntity?,
    val episodeCount: Int,
    val bytes: Long,
)

/**
 * The offline library, for one user (SAFEGUARDING). Every query takes `(serverId, userId)` and returns a download only when
 * that user has a `visible_items` record for the item (on the series screens, for the series and the episode's season too) AND either owns it or the
 * server's "Share downloads between profiles" is on and the download is COMPLETE ([MINE_OR_SHARED]). Watched state is always
 * the session user's own `user_data` (LEFT JOINed with the user in the ON clause). Items never cross servers.
 *
 * The library lists (films, series, a series' episodes and seasons) show COMPLETE downloads only; [observeInProgress] is the
 * owner's own unfinished ones; [observeOne] and [observeForSeries] show any state (badges), still under the same rule.
 * Plain joins, `EXISTS` and `CASE`: nothing newer than SQLite 3.22.
 */
@Dao
abstract class OfflineLibraryDao {
    @Query(FILMS_SQL)
    abstract fun observeFilms(serverId: String, userId: String): Flow<List<OfflineRow>>

    @Query(SERIES_SQL)
    abstract fun observeSeries(serverId: String, userId: String): Flow<List<SeriesSummaryRow>>

    @Query(SERIES_EPISODES_SQL)
    abstract fun observeSeriesEpisodes(serverId: String, userId: String, seriesId: String): Flow<List<OfflineRow>>

    @Query(SEASONS_SQL)
    abstract fun observeSeasons(serverId: String, userId: String, seriesId: String): Flow<List<ItemRow>>

    /** The user's OWN downloads that are not ready yet, in queue order (a shared download is never "in progress" for a non-owner). */
    @Query(IN_PROGRESS_SQL)
    abstract fun observeInProgress(serverId: String, userId: String): Flow<List<OfflineRow>>

    @Query(ONE_SQL)
    abstract fun observeOne(serverId: String, userId: String, itemId: String): Flow<OfflineRow?>

    /** Every download of the series this user may see, in any state, in episode order (for the online series screen's badges). */
    @Query(FOR_SERIES_SQL)
    abstract fun observeForSeries(serverId: String, userId: String, seriesId: String): Flow<List<OfflineRow>>

    /** The download the player may open: COMPLETE, visible to the user, and theirs or shared. */
    @Query(PLAYABLE_SQL)
    internal abstract suspend fun getPlayable(serverId: String, userId: String, itemId: String): DownloadEntity?

    companion object {
        /**
         * The user owns the download, or the server shares downloads between profiles, it is ready, AND the server confirmed this
         * user may download the item ([CONFIRMED]: the strict sharing rule; unknown or false is not shared) (`d` = downloads).
         */
        const val MINE_OR_SHARED =
            "(EXISTS (SELECT 1 FROM download_owners o WHERE o.serverId = d.serverId AND o.itemId = d.itemId AND o.userId = :userId) " +
                "OR (d.state = 'COMPLETE' AND EXISTS (SELECT 1 FROM download_settings x WHERE x.serverId = d.serverId AND x.shareBetweenProfiles = 1) " +
                "AND EXISTS (SELECT 1 FROM download_permissions p WHERE p.serverId = d.serverId AND p.itemId = d.itemId AND p.userId = :userId)))"

        /** The server confirmed this user may download the item. */
        const val CONFIRMED = "EXISTS (SELECT 1 FROM download_permissions p WHERE p.serverId = d.serverId AND p.itemId = d.itemId AND p.userId = :userId)"

        const val MINE = "EXISTS (SELECT 1 FROM download_owners o WHERE o.serverId = d.serverId AND o.itemId = d.itemId AND o.userId = :userId)"

        /** The server showed this user the item's series (the series screens need it as well as the episode's own record). */
        const val SERIES_VISIBLE = "EXISTS (SELECT 1 FROM visible_items sv WHERE sv.serverId = d.serverId AND sv.userId = :userId AND sv.itemId = d.seriesId)"

        /** The episode has no season, or the server showed this user its season (the series screens group episodes under it). */
        const val SEASON_VISIBLE =
            "(d.seasonId IS NULL OR EXISTS (SELECT 1 FROM visible_items qv WHERE qv.serverId = d.serverId AND qv.userId = :userId AND qv.itemId = d.seasonId))"

        const val VISIBLE_JOIN = "JOIN visible_items v ON v.serverId = d.serverId AND v.userId = :userId AND v.itemId = d.itemId "

        /** downloads, the shared item row, the user's visibility record (required) and the user's own data (optional). */
        const val FROM_DOWNLOADS =
            "FROM downloads d JOIN items i ON i.serverId = d.serverId AND i.itemId = d.itemId " + VISIBLE_JOIN +
                "LEFT JOIN user_data u ON u.serverId = d.serverId AND u.userId = :userId AND u.itemId = d.itemId "

        const val DOWNLOAD_COLUMNS =
            "d.serverId AS d_serverId, d.itemId AS d_itemId, d.kind AS d_kind, d.seriesId AS d_seriesId, d.seasonId AS d_seasonId, " +
                "d.quality AS d_quality, d.state AS d_state, d.failure AS d_failure, d.storage AS d_storage, d.volumeId AS d_volumeId, " +
                "d.mediaSourceId AS d_mediaSourceId, d.container AS d_container, d.totalBytes AS d_totalBytes, d.doneBytes AS d_doneBytes, " +
                "d.segmentsTotal AS d_segmentsTotal, d.segmentsDone AS d_segmentsDone, d.validator AS d_validator, d.restarts AS d_restarts, " +
                "d.attempts AS d_attempts, d.runtimeMs AS d_runtimeMs, d.audioIndex AS d_audioIndex, d.sha256 AS d_sha256, " +
                "d.queuePosition AS d_queuePosition, d.requestedAt AS d_requestedAt, d.completedAt AS d_completedAt, d.verifiedAt AS d_verifiedAt, " +
                MINE + " AS ownedByMe "

        const val SELECT_OFFLINE = "SELECT $ROW_COLUMNS, $DOWNLOAD_COLUMNS $FROM_DOWNLOADS"

        /** ItemDao.EPISODES_SQL's order: numbered seasons, then no number, Specials last; episode number (missing last); sort name; id. */
        const val EPISODE_ORDER =
            "ORDER BY CASE WHEN i.parentIndexNumber IS NULL THEN 1 WHEN i.parentIndexNumber = 0 THEN 2 ELSE 0 END, COALESCE(i.parentIndexNumber, 0), " +
                "i.indexNumber IS NULL, i.indexNumber, COALESCE(i.sortName, i.name), i.itemId"

        const val FILMS_SQL =
            "$SELECT_OFFLINE WHERE d.serverId = :serverId AND d.kind = 'MOVIE' AND d.state = 'COMPLETE' AND $MINE_OR_SHARED ORDER BY i.nameKey, i.itemId"

        /** `+d.state` keeps SQLite on the (serverId, seriesId) index rather than the queue's (state, queuePosition) one (LibraryPerformanceTest). */
        const val SERIES_EPISODES_SQL =
            "$SELECT_OFFLINE WHERE d.serverId = :serverId AND d.seriesId = :seriesId AND d.kind = 'EPISODE' AND +d.state = 'COMPLETE' " +
                "AND $MINE_OR_SHARED AND $SERIES_VISIBLE AND $SEASON_VISIBLE $EPISODE_ORDER"

        const val FOR_SERIES_SQL =
            "$SELECT_OFFLINE WHERE d.serverId = :serverId AND d.seriesId = :seriesId AND $MINE_OR_SHARED AND $SERIES_VISIBLE AND $SEASON_VISIBLE $EPISODE_ORDER"

        const val IN_PROGRESS_SQL = "$SELECT_OFFLINE WHERE d.serverId = :serverId AND d.state <> 'COMPLETE' AND $MINE ORDER BY d.queuePosition"

        const val ONE_SQL = "$SELECT_OFFLINE WHERE d.serverId = :serverId AND d.itemId = :itemId AND $MINE_OR_SHARED"

        const val PLAYABLE_SQL =
            "SELECT d.* FROM downloads d JOIN items i ON i.serverId = d.serverId AND i.itemId = d.itemId $VISIBLE_JOIN" +
                "WHERE d.serverId = :serverId AND d.itemId = :itemId AND d.state = 'COMPLETE' AND $MINE_OR_SHARED"

        /** One row per series (`i` is the series here, `e` the episode). */
        const val SERIES_SQL =
            "SELECT $ROW_COLUMNS, COUNT(*) AS episodeCount, SUM(COALESCE(d.totalBytes, d.doneBytes)) AS bytes " +
                "FROM downloads d JOIN items e ON e.serverId = d.serverId AND e.itemId = d.itemId " + VISIBLE_JOIN +
                "JOIN items i ON i.serverId = d.serverId AND i.itemId = d.seriesId " +
                "LEFT JOIN user_data u ON u.serverId = d.serverId AND u.userId = :userId AND u.itemId = d.seriesId " +
                "WHERE d.serverId = :serverId AND d.kind = 'EPISODE' AND d.state = 'COMPLETE' AND $MINE_OR_SHARED AND $SERIES_VISIBLE AND $SEASON_VISIBLE " +
                "GROUP BY i.serverId, i.itemId ORDER BY i.nameKey, i.itemId"

        /** The seasons of a series that hold a ready episode this user may see; the season row needs its own visibility record. */
        const val SEASONS_SQL =
            "SELECT $ROW_COLUMNS FROM items i JOIN visible_items v ON v.serverId = i.serverId AND v.userId = :userId AND v.itemId = i.itemId " +
                "LEFT JOIN user_data u ON u.serverId = i.serverId AND u.userId = :userId AND u.itemId = i.itemId " +
                "WHERE i.serverId = :serverId AND i.itemId IN (SELECT d.seasonId FROM downloads d " +
                "JOIN items e ON e.serverId = d.serverId AND e.itemId = d.itemId " + VISIBLE_JOIN +
                "WHERE d.serverId = :serverId AND d.seriesId = :seriesId AND d.kind = 'EPISODE' AND d.state = 'COMPLETE' AND d.seasonId IS NOT NULL " +
                "AND $MINE_OR_SHARED AND $SERIES_VISIBLE) " +
                "ORDER BY CASE WHEN i.indexNumber IS NULL THEN 1 WHEN i.indexNumber = 0 THEN 2 ELSE 0 END, COALESCE(i.indexNumber, 0), " +
                "COALESCE(i.sortName, i.name), i.itemId"
    }
}
