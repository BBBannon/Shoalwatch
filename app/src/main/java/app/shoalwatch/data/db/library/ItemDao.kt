package app.shoalwatch.data.db.library

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import app.shoalwatch.data.library.ItemKind
import kotlinx.coroutines.flow.Flow

/** Items (per server) and what each user has done with them (per server and user). */
@Dao
abstract class ItemDao {
    // ---- items

    @Upsert
    protected abstract suspend fun upsertRaw(items: List<ItemEntity>)

    @Query("SELECT * FROM items WHERE serverId = :serverId AND itemId IN (:ids)")
    protected abstract suspend fun getEntities(serverId: String, ids: List<String>): List<ItemEntity>

    /**
     * Writes items from a server response, never an unconditional replace of what is stored (the read and the write share
     * this transaction, so nothing can change in between):
     *  - `pinned` is never touched (it belongs to downloads, not to the server's answer);
     *  - `overview` and `childCount` are kept when the incoming value is null, because list endpoints do not request them and
     *    a list response must not erase what a detail fetch stored. Every other column takes the incoming value.
     * Every row in [items] must be for [serverId].
     */
    @Transaction
    open suspend fun upsertItems(serverId: String, items: List<ItemEntity>) {
        require(items.all { it.serverId == serverId }) { "Every item must belong to the server being written." }
        val existing = HashMap<String, ItemEntity>()
        items.map { it.itemId }.distinct().chunked(ID_CHUNK).forEach { ids -> getEntities(serverId, ids).forEach { existing[it.itemId] = it } }
        upsertRaw(
            items.map { new ->
                val old = existing[new.itemId] ?: return@map new
                new.copy(overview = new.overview ?: old.overview, childCount = new.childCount ?: old.childCount, pinned = old.pinned)
            },
        )
    }

    @Query("SELECT * FROM items WHERE serverId = :serverId AND itemId = :itemId")
    abstract suspend fun getItemEntity(serverId: String, itemId: String): ItemEntity?

    @Query("SELECT COUNT(*) FROM items WHERE serverId = :serverId")
    abstract suspend fun countItems(serverId: String): Int

    // ---- reading items joined to one user's data

    @Query(ONE_SQL)
    abstract fun observeItem(serverId: String, userId: String, itemId: String): Flow<ItemRow?>

    @Query(ONE_SQL)
    abstract suspend fun getItem(serverId: String, userId: String, itemId: String): ItemRow?

    /**
     * A series' stored episodes (all of them when [seasonId] is null) by season number then episode number, missing numbers
     * last, then sort name and id. Reads the items directly, so it shows whatever is stored whether or not a list names it.
     */
    @Query(EPISODES_SQL)
    abstract fun observeEpisodes(serverId: String, userId: String, seriesId: String, seasonId: String?): Flow<List<ItemRow>>

    @Query(EPISODES_SQL)
    abstract suspend fun getEpisodes(serverId: String, userId: String, seriesId: String, seasonId: String?): List<ItemRow>

    @Query(SEARCH_SQL)
    protected abstract suspend fun searchByPattern(serverId: String, userId: String, pattern: String, kinds: List<String>, limit: Int): List<ItemRow>

    /**
     * Stored items of [kinds] whose name contains [query], for searching offline. Case-insensitive in every script. The
     * `%`, `_` and `\` in [query] are matched literally. A blank query matches nothing. No kinds means every kind.
     */
    suspend fun searchByName(serverId: String, userId: String, query: String, kinds: Set<ItemKind>, limit: Int): List<ItemRow> {
        val text = searchKey(query.trim())
        if (text.isEmpty() || limit <= 0) return emptyList()
        val escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return searchByPattern(serverId, userId, "%$escaped%", kinds.ifEmpty { ItemKind.entries.toSet() }.map { it.name }, limit)
    }

    // ---- user data

    @Upsert
    abstract suspend fun upsertUserData(rows: List<UserDataEntity>)

    @Query("SELECT * FROM user_data WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId")
    abstract suspend fun getUserData(serverId: String, userId: String, itemId: String): UserDataEntity?

    /**
     * Makes sure the user has a row for the item, with defaults, without touching an existing one. Plain `INSERT OR IGNORE`
     * plus `UPDATE`, never SQL UPSERT (`ON CONFLICT ... DO UPDATE`): that needs SQLite 3.24 and Android 10 ships 3.22
     * (`SqlCompatibilityTest` keeps it out).
     */
    @Query(
        "INSERT OR IGNORE INTO user_data (serverId, userId, itemId, played, positionMs, playedPercentage, playCount, favourite, lastPlayed, unplayedItemCount, updatedAt) " +
            "VALUES (:serverId, :userId, :itemId, 0, 0, NULL, 0, 0, NULL, NULL, :nowMs)",
    )
    protected abstract suspend fun ensureUserData(serverId: String, userId: String, itemId: String, nowMs: Long)

    @Query(
        "UPDATE user_data SET played = :played, positionMs = :positionMs, playedPercentage = NULL, " +
            "playCount = CASE WHEN :played THEN MAX(playCount, 1) ELSE 0 END, " +
            "lastPlayed = CASE WHEN :played THEN :nowMs ELSE lastPlayed END, updatedAt = :nowMs " +
            "WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId",
    )
    protected abstract suspend fun updatePlayed(serverId: String, userId: String, itemId: String, played: Boolean, positionMs: Long, nowMs: Long)

    @Query("UPDATE user_data SET favourite = :favourite, updatedAt = :nowMs WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId")
    protected abstract suspend fun updateFavourite(serverId: String, userId: String, itemId: String, favourite: Boolean, nowMs: Long)

    @Query(
        "UPDATE user_data SET positionMs = :positionMs, playedPercentage = NULL, " +
            "lastPlayed = CASE WHEN :positionMs > 0 THEN :nowMs ELSE lastPlayed END, updatedAt = :nowMs " +
            "WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId",
    )
    protected abstract suspend fun updatePosition(serverId: String, userId: String, itemId: String, positionMs: Long, nowMs: Long)

    /**
     * Marks an item watched or not for this user, creating the row if there is none. Watched sets the last-played time and
     * keeps at least one play; not watched resets the play count. [positionMs] is stored as given (a watched item has 0).
     * The percentage is cleared (it is derived from position and runtime). [nowMs] is stored as `updatedAt`.
     */
    @Transaction
    open suspend fun setPlayed(serverId: String, userId: String, itemId: String, played: Boolean, positionMs: Long, nowMs: Long) {
        ensureUserData(serverId, userId, itemId, nowMs)
        updatePlayed(serverId, userId, itemId, played, positionMs, nowMs)
    }

    /** Sets favourite for this user, creating the row if there is none. */
    @Transaction
    open suspend fun setFavourite(serverId: String, userId: String, itemId: String, favourite: Boolean, nowMs: Long) {
        ensureUserData(serverId, userId, itemId, nowMs)
        updateFavourite(serverId, userId, itemId, favourite, nowMs)
    }

    /** Records where playback stopped for this user (0 clears it), creating the row if there is none. Does not change watched. */
    @Transaction
    open suspend fun setPosition(serverId: String, userId: String, itemId: String, positionMs: Long, nowMs: Long) {
        ensureUserData(serverId, userId, itemId, nowMs)
        updatePosition(serverId, userId, itemId, positionMs, nowMs)
    }

    companion object {
        /** Well under SQLite's bound-variable limit on every Android version. */
        private const val ID_CHUNK = 500

        private const val FROM_ITEMS_AND_USER_DATA =
            "FROM items i LEFT JOIN user_data u ON u.serverId = i.serverId AND u.userId = :userId AND u.itemId = i.itemId "

        private const val ONE_SQL = "SELECT $ROW_COLUMNS $FROM_ITEMS_AND_USER_DATA WHERE i.serverId = :serverId AND i.itemId = :itemId"

        /** Public so a test can ask SQLite for its query plan. */
        const val EPISODES_SQL =
            "SELECT $ROW_COLUMNS $FROM_ITEMS_AND_USER_DATA" +
                "WHERE i.serverId = :serverId AND i.seriesId = :seriesId AND i.kind = 'EPISODE' AND (:seasonId IS NULL OR i.seasonId = :seasonId) " +
                "ORDER BY i.parentIndexNumber IS NULL, i.parentIndexNumber, i.indexNumber IS NULL, i.indexNumber, COALESCE(i.sortName, i.name) COLLATE NOCASE, i.itemId"

        private const val SEARCH_SQL =
            "SELECT $ROW_COLUMNS $FROM_ITEMS_AND_USER_DATA" +
                "WHERE i.serverId = :serverId AND i.kind IN (:kinds) AND i.nameKey LIKE :pattern ESCAPE '\\' ORDER BY i.nameKey, i.itemId LIMIT :limit"
    }
}
