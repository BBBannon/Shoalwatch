package app.shoalwatch.data.db.downloads

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/** `offline_plays`: what each profile watched from a saved copy (INSERT OR IGNORE + UPDATE, never REPLACE). */
@Dao
abstract class OfflinePlayDao {
    @Query("SELECT * FROM offline_plays WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId")
    internal abstract suspend fun get(serverId: String, userId: String, itemId: String): OfflinePlayEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertOrIgnore(row: OfflinePlayEntity): Long

    @Query("UPDATE offline_plays SET positionMs = :positionMs, lastPlayedAt = :nowMs, updatedAt = :nowMs WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId")
    protected abstract suspend fun updatePosition(serverId: String, userId: String, itemId: String, positionMs: Long, nowMs: Long)

    /** `played` is only ever set, never cleared; `playedAt` is set once, the first time it is finished. */
    @Query(
        "UPDATE offline_plays SET positionMs = :positionMs, played = CASE WHEN :finished THEN 1 ELSE played END, " +
            "playedAt = CASE WHEN :finished AND playedAt IS NULL THEN :nowMs ELSE playedAt END, lastPlayedAt = :nowMs, updatedAt = :nowMs " +
            "WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId",
    )
    protected abstract suspend fun updateStop(serverId: String, userId: String, itemId: String, positionMs: Long, finished: Boolean, nowMs: Long)

    @Query("SELECT COUNT(*) FROM offline_plays WHERE serverId = :serverId AND userId = :userId")
    internal abstract fun count(serverId: String, userId: String): Flow<Int>

    @Transaction
    internal open suspend fun position(serverId: String, userId: String, itemId: String, positionMs: Long, nowMs: Long) {
        insertOrIgnore(OfflinePlayEntity(serverId, userId, itemId, positionMs, played = false, playedAt = null, lastPlayedAt = nowMs, updatedAt = nowMs))
        updatePosition(serverId, userId, itemId, positionMs, nowMs)
    }

    @Transaction
    internal open suspend fun stopped(serverId: String, userId: String, itemId: String, positionMs: Long, finished: Boolean, nowMs: Long) {
        insertOrIgnore(OfflinePlayEntity(serverId, userId, itemId, positionMs, played = false, playedAt = null, lastPlayedAt = nowMs, updatedAt = nowMs))
        updateStop(serverId, userId, itemId, positionMs, finished, nowMs)
    }
}
