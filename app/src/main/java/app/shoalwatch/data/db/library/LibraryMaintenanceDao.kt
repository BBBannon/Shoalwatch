package app.shoalwatch.data.db.library

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction

/** How many rows one [LibraryMaintenanceDao.evict] removed. */
data class EvictionCounts(val listEntries: Int, val listStates: Int, val items: Int, val userData: Int, val visibility: Int = 0)

/**
 * Keeps the cache bounded. Everything here is per server, and [evict] is one transaction.
 *
 * An item row is *protected* (never deleted) when it is pinned, is named by any list entry on its server, or is the
 * `seriesId`, `seasonId` or `parentId` of such an item (a pinned or listed episode keeps its season and series).
 */
@Dao
abstract class LibraryMaintenanceDao {
    @Query("SELECT id FROM servers")
    abstract suspend fun serverIds(): List<String>

    /** Entries of lists with no state refreshed at or after [cutoffMs] (including lists with no state at all). */
    @Query(
        "DELETE FROM list_entries WHERE serverId = :serverId AND NOT EXISTS (SELECT 1 FROM list_state s WHERE s.serverId = list_entries.serverId " +
            "AND s.userId = list_entries.userId AND s.listKey = list_entries.listKey AND s.refreshedAt >= :cutoffMs)",
    )
    protected abstract suspend fun deleteStaleEntries(serverId: String, cutoffMs: Long): Int

    @Query("DELETE FROM list_state WHERE serverId = :serverId AND refreshedAt < :cutoffMs")
    protected abstract suspend fun deleteStaleStates(serverId: String, cutoffMs: Long): Int

    @Query("DELETE FROM items WHERE $UNPROTECTED AND fetchedAt < :cutoffMs")
    protected abstract suspend fun deleteOldItems(serverId: String, cutoffMs: Long): Int

    @Query("SELECT COUNT(*) FROM items WHERE serverId = :serverId AND pinned = 0")
    protected abstract suspend fun countUnpinned(serverId: String): Int

    @Query("DELETE FROM items WHERE rowid IN (SELECT rowid FROM items WHERE $UNPROTECTED ORDER BY fetchedAt, itemId LIMIT :count)")
    protected abstract suspend fun deleteOldest(serverId: String, count: Int): Int

    /** Watched state must survive eviction of metadata: rows that are watched, favourite, have a resume position or hold an unsynced local change (`pendingSyncAt`) stay. */
    @Query(
        "DELETE FROM user_data WHERE serverId = :serverId AND played = 0 AND favourite = 0 AND positionMs <= 0 AND pendingSyncAt IS NULL " +
            "AND NOT EXISTS (SELECT 1 FROM items i WHERE i.serverId = user_data.serverId AND i.itemId = user_data.itemId)",
    )
    protected abstract suspend fun deleteOrphanedUserData(serverId: String): Int

    /** The record of what the server showed each user, for items that no longer exist (pinned items still exist, so theirs stays). */
    @Query("DELETE FROM visible_items WHERE serverId = :serverId AND NOT EXISTS (SELECT 1 FROM items i WHERE i.serverId = visible_items.serverId AND i.itemId = visible_items.itemId)")
    protected abstract suspend fun deleteOrphanedVisibility(serverId: String): Int

    /**
     * One transaction, in this order:
     *  1. delete list entries and states for lists not refreshed since [listCutoffMs];
     *  2. delete unprotected items fetched before [itemCutoffMs];
     *  3. if the server still has more than [maxUnpinnedItems] unpinned items, delete the oldest-fetched unprotected ones
     *     until it does not (protected rows are never deleted, so the cap can be exceeded only by protected rows);
     *  4. delete user data whose item is gone, except rows that are watched, favourite, have a resume position or are pending sync;
     *  5. delete the `visible_items` record of items that are gone.
     */
    @Transaction
    open suspend fun evict(serverId: String, listCutoffMs: Long, itemCutoffMs: Long, maxUnpinnedItems: Int): EvictionCounts {
        val entries = deleteStaleEntries(serverId, listCutoffMs)
        val states = deleteStaleStates(serverId, listCutoffMs)
        var items = deleteOldItems(serverId, itemCutoffMs)
        val excess = countUnpinned(serverId) - maxUnpinnedItems.coerceAtLeast(0)
        if (excess > 0) items += deleteOldest(serverId, excess)
        val userData = deleteOrphanedUserData(serverId)
        return EvictionCounts(entries, states, items, userData, deleteOrphanedVisibility(serverId))
    }

    private companion object {
        const val LISTED = "SELECT itemId FROM list_entries WHERE serverId = :serverId"

        /** The links of every pinned or listed item. NULLs are filtered out because a NULL inside NOT IN would make it match nothing. */
        const val PROTECTED_LINKS =
            "SELECT x.seriesId AS id FROM items x WHERE x.serverId = :serverId AND (x.pinned = 1 OR x.itemId IN ($LISTED)) AND x.seriesId IS NOT NULL " +
                "UNION SELECT x.seasonId FROM items x WHERE x.serverId = :serverId AND (x.pinned = 1 OR x.itemId IN ($LISTED)) AND x.seasonId IS NOT NULL " +
                "UNION SELECT x.parentId FROM items x WHERE x.serverId = :serverId AND (x.pinned = 1 OR x.itemId IN ($LISTED)) AND x.parentId IS NOT NULL"

        const val UNPROTECTED = "serverId = :serverId AND pinned = 0 AND itemId NOT IN ($LISTED) AND itemId NOT IN ($PROTECTED_LINKS)"
    }
}
