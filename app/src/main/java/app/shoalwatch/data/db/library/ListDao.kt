package app.shoalwatch.data.db.library

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Lists as ordered membership plus state. Reading joins to the items and the user's data; writing a page together with its
 * items is `LibraryStore.writePage` (one transaction across the DAOs).
 */
@Dao
interface ListDao {
    /** The list in position order, at most [limit] rows. An entry whose item row is missing, or that the server never showed this user (`visible_items`), is not returned. */
    @Query(LIST_SQL)
    fun observeList(serverId: String, userId: String, listKey: String, limit: Int): Flow<List<ItemRow>>

    @Query(LIST_SQL)
    suspend fun getList(serverId: String, userId: String, listKey: String, limit: Int): List<ItemRow>

    @Query("SELECT * FROM list_state WHERE serverId = :serverId AND userId = :userId AND listKey = :listKey")
    fun observeState(serverId: String, userId: String, listKey: String): Flow<ListStateEntity?>

    @Query("SELECT * FROM list_state WHERE serverId = :serverId AND userId = :userId AND listKey = :listKey")
    suspend fun getState(serverId: String, userId: String, listKey: String): ListStateEntity?

    /** The ids in a list in position order (no join, so cheap for a long list); for de-duplicating the next page. */
    @Query("SELECT itemId FROM list_entries WHERE serverId = :serverId AND userId = :userId AND listKey = :listKey ORDER BY position")
    suspend fun entryIds(serverId: String, userId: String, listKey: String): List<String>

    /** The keys of this user's lists that hold the item. */
    @Query("SELECT DISTINCT listKey FROM list_entries WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId")
    suspend fun keysContaining(serverId: String, userId: String, itemId: String): List<String>

    @Query("SELECT COUNT(*) FROM list_entries WHERE serverId = :serverId AND userId = :userId AND listKey = :listKey")
    suspend fun countEntries(serverId: String, userId: String, listKey: String): Int

    @Query("SELECT MAX(position) FROM list_entries WHERE serverId = :serverId AND userId = :userId AND listKey = :listKey")
    suspend fun maxPosition(serverId: String, userId: String, listKey: String): Int?

    @Query("SELECT MIN(position) FROM list_entries WHERE serverId = :serverId AND userId = :userId AND listKey = :listKey AND itemId = :itemId")
    suspend fun firstPositionOf(serverId: String, userId: String, listKey: String, itemId: String): Int?

    /** The entries from [start] on, in position order. */
    @Query("SELECT * FROM list_entries WHERE serverId = :serverId AND userId = :userId AND listKey = :listKey AND position >= :start ORDER BY position")
    suspend fun entriesFrom(serverId: String, userId: String, listKey: String, start: Int): List<ListEntryEntity>

    @Query("DELETE FROM list_entries WHERE serverId = :serverId AND userId = :userId AND listKey = :listKey AND position >= :start")
    suspend fun deleteEntriesFrom(serverId: String, userId: String, listKey: String, start: Int)

    /** Plain insert: callers delete the positions first, so a clash is a bug and must fail loudly. */
    @Insert
    suspend fun insertEntries(entries: List<ListEntryEntity>)

    /** Makes every list of this user [byMs] older, so each reads as stale and is refreshed the next time it is collected (the saved rows stay). */
    @Query("UPDATE list_state SET refreshedAt = refreshedAt - :byMs WHERE serverId = :serverId AND userId = :userId")
    suspend fun ageLists(serverId: String, userId: String, byMs: Long)

    @Upsert
    suspend fun upsertState(state: ListStateEntity)

    companion object {
        const val LIST_SQL =
            "SELECT $ROW_COLUMNS FROM list_entries e JOIN items i ON i.serverId = e.serverId AND i.itemId = e.itemId " +
                "JOIN visible_items v ON v.serverId = e.serverId AND v.userId = e.userId AND v.itemId = e.itemId " +
                "LEFT JOIN user_data u ON u.serverId = e.serverId AND u.userId = e.userId AND u.itemId = e.itemId " +
                "WHERE e.serverId = :serverId AND e.userId = :userId AND e.listKey = :listKey ORDER BY e.position LIMIT :limit"
    }
}
