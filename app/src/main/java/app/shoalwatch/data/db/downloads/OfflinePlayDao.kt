package app.shoalwatch.data.db.downloads

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

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

    /** A confirmed outbox row covered this play (it was written at or before the change the row carried): it has been sent. */
    @Query("DELETE FROM offline_plays WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId AND updatedAt <= :occurredAt")
    internal abstract suspend fun deleteCovered(serverId: String, userId: String, itemId: String, occurredAt: Long): Int

    /** Plays no outbox row from an offline play covers (a process that died mid-play, or between a stop's two writes). */
    @Query(
        "SELECT * FROM offline_plays p WHERE NOT EXISTS (SELECT 1 FROM sync_outbox o WHERE o.serverId = p.serverId AND o.userId = p.userId " +
            "AND o.itemId = p.itemId AND o.fromOfflinePlay = 1)",
    )
    internal abstract suspend fun unqueued(): List<OfflinePlayEntity>

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
