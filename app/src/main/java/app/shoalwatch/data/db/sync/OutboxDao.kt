package app.shoalwatch.data.db.sync

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import app.shoalwatch.data.library.ItemKind
import kotlinx.coroutines.flow.Flow

/**
 * `sync_outbox` and `sync_marks`, reached only through `OutboxStore` (`data/sync`), the one writer of both. Rows are read with
 * `kind` as text ([OutboxRow]): a kind a later version wrote cannot be read into [OutboxEntity] (its converter gives null), so the
 * store deletes such a row instead. Inserts are plain (abort on conflict) or `INSERT OR IGNORE`, never `REPLACE`.
 */
@Dao
abstract class OutboxDao {
    @Insert
    internal abstract suspend fun insert(row: OutboxEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    internal abstract suspend fun insertMarks(marks: List<SyncMarkEntity>)

    /** Rows for one profile after [afterSeq], in send order. */
    @Query("SELECT * FROM sync_outbox WHERE serverId = :serverId AND userId = :userId AND seq > :afterSeq ORDER BY seq LIMIT :limit")
    internal abstract suspend fun rows(serverId: String, userId: String, afterSeq: Long, limit: Int): List<OutboxRow>

    @Query("SELECT * FROM sync_outbox WHERE seq = :seq")
    internal abstract suspend fun row(seq: Long): OutboxRow?

    /** The rows a new change of [kind] for this item replaces (every row here is unsent or in flight). */
    @Query("SELECT seq FROM sync_outbox WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId AND kind = :kind")
    internal abstract suspend fun sameChange(serverId: String, userId: String, itemId: String, kind: String): List<Long>

    @Query("SELECT * FROM sync_marks WHERE seq = :seq")
    internal abstract suspend fun marksOf(seq: Long): List<SyncMarkEntity>

    @Query("DELETE FROM sync_marks WHERE seq = :seq")
    internal abstract suspend fun deleteMarks(seq: Long)

    @Query("DELETE FROM sync_outbox WHERE seq = :seq")
    internal abstract suspend fun deleteRow(seq: Long): Int

    @Query("SELECT EXISTS(SELECT 1 FROM sync_marks WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId)")
    internal abstract suspend fun hasMark(serverId: String, userId: String, itemId: String): Boolean

    @Query("SELECT DISTINCT itemId FROM sync_marks WHERE serverId = :serverId AND userId = :userId AND leftList = :listKey")
    internal abstract suspend fun leftList(serverId: String, userId: String, listKey: String): List<String>

    /** `sentPlayCount` is set once: a retry keeps the count the first send carried. */
    @Query(
        "UPDATE sync_outbox SET sentOnce = 1, attempts = attempts + 1, sentPlayCount = COALESCE(sentPlayCount, :playCount) WHERE seq = :seq",
    )
    internal abstract suspend fun markSending(seq: Long, playCount: Int?)

    @Query("UPDATE sync_outbox SET serverErrors = :serverErrors, notBefore = :notBefore WHERE seq = :seq")
    internal abstract suspend fun defer(seq: Long, serverErrors: Int, notBefore: Long)

    @Query(
        "SELECT COUNT(*) AS waiting, COALESCE(SUM(CASE WHEN serverErrors >= :stuckAfter THEN 1 ELSE 0 END), 0) AS stuck " +
            "FROM sync_outbox WHERE serverId = :serverId AND userId = :userId",
    )
    internal abstract fun observeCounts(serverId: String, userId: String, stuckAfter: Int): Flow<OutboxCounts>

    @Query("SELECT lastSyncedAt FROM sync_state WHERE serverId = :serverId AND userId = :userId")
    internal abstract fun observeLastSynced(serverId: String, userId: String): Flow<Long?>

    @Query("SELECT COUNT(*) FROM sync_outbox WHERE serverId = :serverId AND userId = :userId")
    internal abstract suspend fun count(serverId: String, userId: String): Int

    /** Every profile with rows, the one whose oldest row is oldest first. */
    @Query("SELECT serverId, userId FROM sync_outbox GROUP BY serverId, userId ORDER BY MIN(seq)")
    internal abstract suspend fun accountsWithWork(): List<OutboxAccount>

    @Query("SELECT DISTINCT serverId FROM sync_outbox")
    internal abstract fun serversWithWork(): Flow<List<String>>

    /** Only for an account that still exists (a foreign key failure is not ignored by OR IGNORE). */
    @Query(
        "INSERT OR IGNORE INTO sync_state (serverId, userId, lastSyncedAt) " +
            "SELECT serverId, userId, :nowMs FROM accounts WHERE serverId = :serverId AND userId = :userId",
    )
    internal abstract suspend fun insertSynced(serverId: String, userId: String, nowMs: Long)

    @Query("UPDATE sync_state SET lastSyncedAt = :nowMs WHERE serverId = :serverId AND userId = :userId")
    internal abstract suspend fun updateSynced(serverId: String, userId: String, nowMs: Long)
}

/** An outbox row as stored, with [kind] as text (see [OutboxDao]). */
internal data class OutboxRow(
    val seq: Long,
    val serverId: String,
    val userId: String,
    val itemId: String,
    val itemKind: ItemKind,
    val kind: String,
    val flag: Boolean?,
    val positionMs: Long?,
    val occurredAt: Long,
    val refreshKeys: String,
    val fromOfflinePlay: Boolean,
    val sentOnce: Boolean,
    val sentPlayCount: Int?,
    val attempts: Int,
    val serverErrors: Int,
    val notBefore: Long,
) {
    override fun toString() = "OutboxRow(<redacted>)"
}

internal data class OutboxCounts(val waiting: Int, val stuck: Int)

internal data class OutboxAccount(val serverId: String, val userId: String) {
    override fun toString() = "OutboxAccount(<redacted>)"
}
