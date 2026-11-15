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
     *  - `overview` is kept when the incoming value is null, because list endpoints do not request it and a list response must
     *    not erase what a detail fetch stored, unless [authoritative] (a detail fetch, which does request it): then a null is a
     *    null and clears it;
     *  - `childCount` is ALWAYS taken as sent, null included (null means unknown, and the UI shows no badge): Jellyfin's count is
     *    per user, and the item row is shared, so a kept value could be another user's number.
     *  Every other column takes the incoming value.
     * Only [LibraryStore] calls this (it is `internal`), so `nameKey` is always built by the one mapper.
     * Every row in [items] must be for [serverId].
     */
    @Transaction
    internal open suspend fun upsertItems(serverId: String, items: List<ItemEntity>, authoritative: Boolean = false) {
        require(items.all { it.serverId == serverId }) { "Every item must belong to the server being written." }
        val existing = HashMap<String, ItemEntity>()
        items.map { it.itemId }.distinct().chunked(ID_CHUNK).forEach { ids -> getEntities(serverId, ids).forEach { existing[it.itemId] = it } }
        upsertRaw(
            items.map { new ->
                val old = existing[new.itemId] ?: return@map new
                if (authoritative) new.copy(pinned = old.pinned)
                else new.copy(overview = new.overview ?: old.overview, pinned = old.pinned)
            },
        )
    }

    @Upsert
    protected abstract suspend fun upsertVisible(rows: List<VisibleItemEntity>)

    /** Records that the server showed these items to this user (see [VisibleItemEntity]). Only [LibraryStore] calls this, in the transaction that writes the items. */
    internal suspend fun markVisible(serverId: String, userId: String, itemIds: List<String>, seenAt: Long) =
        upsertVisible(itemIds.distinct().map { VisibleItemEntity(serverId, userId, it, seenAt) })

    /** Takes the item away from ONE user (the server refused it for them): their record only; the shared row and other users' records stay. */
    @Query("DELETE FROM visible_items WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId")
    internal abstract suspend fun deleteVisible(serverId: String, userId: String, itemId: String)

    /** NOT filtered by visibility (the shared row as stored): for the store and tests only, never for anything a user sees. */
    @Query("SELECT * FROM items WHERE serverId = :serverId AND itemId = :itemId")
    internal abstract suspend fun getItemEntity(serverId: String, itemId: String): ItemEntity?

    /** NOT filtered by visibility: for the store and tests only. */
    @Query("SELECT COUNT(*) FROM items WHERE serverId = :serverId")
    internal abstract suspend fun countItems(serverId: String): Int

    // ---- reading items joined to one user's data

    @Query(ONE_SQL)
    abstract fun observeItem(serverId: String, userId: String, itemId: String): Flow<ItemRow?>

    @Query(ONE_SQL)
    abstract suspend fun getItem(serverId: String, userId: String, itemId: String): ItemRow?

    /**
     * Every read here and in [ListDao] shows a user only items the server returned to that user ([VisibleItemEntity]); any other is treated as absent.
     *
     * A series' stored episodes (all of them when [seasonId] is null) in the app's one episode order (`data/library/api/Ordering.kt`; a test keeps the two identical):
     * numbered seasons, then no season number, then season 0 (Specials) last of all; then episode number (missing last), sort name (or name), id. Reads the items directly, so it shows whatever is stored whether or not a list names it.
     */
    @Query(EPISODES_SQL)
    abstract fun observeEpisodes(serverId: String, userId: String, seriesId: String, seasonId: String?): Flow<List<ItemRow>>

    @Query(EPISODES_SQL)
    abstract suspend fun getEpisodes(serverId: String, userId: String, seriesId: String, seasonId: String?): List<ItemRow>

    /** A series' stored seasons this user was shown (a season row's own `seriesId`), in season order with Specials last. For the download planner. */
    @Query(SEASONS_SQL)
    abstract suspend fun getSeasons(serverId: String, userId: String, seriesId: String): List<ItemRow>

    @Query(SEARCH_SQL)
    protected abstract suspend fun searchByPattern(serverId: String, userId: String, pattern: String, kinds: List<String>, limit: Int): List<ItemRow>

    @Query(SEARCH_ALL_KINDS_SQL)
    protected abstract suspend fun searchAllKinds(serverId: String, userId: String, pattern: String, limit: Int): List<ItemRow>

    /**
     * Stored items of [kinds] whose name contains [query], for searching offline. Case-insensitive in every script. The
     * `%`, `_` and `\` in [query] are matched literally. A blank query matches nothing. No kinds means every kind, including rows whose stored kind
     * this version does not know. Accents are ignored and both Turkish I letters read as i (see [searchKey]); the same folding is applied to the stored name and the query.
     */
    suspend fun searchByName(serverId: String, userId: String, query: String, kinds: Set<ItemKind>, limit: Int): List<ItemRow> {
        val text = searchKey(query.trim())
        if (text.isEmpty() || limit <= 0) return emptyList()
        val escaped = text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val pattern = "%$escaped%"
        return if (kinds.isEmpty()) searchAllKinds(serverId, userId, pattern, limit) else searchByPattern(serverId, userId, pattern, kinds.map { it.name }, limit)
    }

    // ---- user data

    @Upsert
    protected abstract suspend fun upsertUserRows(rows: List<UserDataEntity>)

    @Query("SELECT itemId FROM user_data WHERE serverId = :serverId AND userId = :userId AND pendingSyncAt IS NOT NULL AND itemId IN (:ids)")
    protected abstract suspend fun pendingAmong(serverId: String, userId: String, ids: List<String>): List<String>

    /**
     * THE one function that stores user data the SERVER provided. A row for an (server, user, item) whose stored row has
     * `pendingSyncAt` set is ignored (it holds a local change the server has not confirmed, and a sync must never undo
     * it, SPEC 3.2); every other row replaces the stored one, and is never itself pending.
     */
    @Transaction
    internal open suspend fun writeServerUserData(rows: List<UserDataEntity>) {
        for ((key, group) in rows.groupBy { it.serverId to it.userId }) {
            val pending = HashSet<String>()
            group.map { it.itemId }.distinct().chunked(ID_CHUNK).forEach { ids -> pending += pendingAmong(key.first, key.second, ids) }
            upsertUserRows(group.filter { it.itemId !in pending }.map { it.copy(pendingSyncAt = null) })
        }
    }

    /** Marks the row as holding an unconfirmed local change; returns 0 if the user has no row for the item. */
    @Query("UPDATE user_data SET pendingSyncAt = :nowMs WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId")
    internal abstract suspend fun markPending(serverId: String, userId: String, itemId: String, nowMs: Long): Int

    /** Every row with an unconfirmed local change, for every user and server (the start-up sweep's snapshot), except one kept for an offline play. */
    @Query("SELECT * FROM user_data WHERE pendingSyncAt IS NOT NULL AND $NO_OFFLINE_PLAY")
    internal abstract suspend fun pendingRows(): List<UserDataEntity>

    /** Clears every marker of one user (the local values stay), except one kept for an offline play; returns how many rows had one. */
    @Query("UPDATE user_data SET pendingSyncAt = NULL WHERE serverId = :serverId AND userId = :userId AND pendingSyncAt IS NOT NULL AND $NO_OFFLINE_PLAY")
    internal abstract suspend fun clearPendingFor(serverId: String, userId: String): Int

    @Query("UPDATE user_data SET pendingSyncAt = NULL WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId")
    internal abstract suspend fun clearPending(serverId: String, userId: String, itemId: String)

    @Query("DELETE FROM user_data WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId")
    internal abstract suspend fun deleteUserData(serverId: String, userId: String, itemId: String)

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
        "UPDATE user_data SET played = :played, positionMs = :positionMs, playedPercentage = NULL, pendingSyncAt = CASE WHEN :pending THEN :nowMs ELSE pendingSyncAt END, " +
            "playCount = CASE WHEN :played THEN MAX(playCount, 1) ELSE 0 END, " +
            "lastPlayed = CASE WHEN :played THEN :nowMs ELSE lastPlayed END, updatedAt = :nowMs " +
            "WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId",
    )
    protected abstract suspend fun updatePlayed(serverId: String, userId: String, itemId: String, played: Boolean, positionMs: Long, nowMs: Long, pending: Boolean)

    @Query("UPDATE user_data SET favourite = :favourite, updatedAt = :nowMs, pendingSyncAt = CASE WHEN :pending THEN :nowMs ELSE pendingSyncAt END WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId")
    protected abstract suspend fun updateFavourite(serverId: String, userId: String, itemId: String, favourite: Boolean, nowMs: Long, pending: Boolean)

    @Query(
        "UPDATE user_data SET positionMs = :positionMs, playedPercentage = NULL, pendingSyncAt = CASE WHEN :pending THEN :nowMs ELSE pendingSyncAt END, " +
            "lastPlayed = CASE WHEN :positionMs > 0 THEN :nowMs ELSE lastPlayed END, updatedAt = :nowMs " +
            "WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId",
    )
    protected abstract suspend fun updatePosition(serverId: String, userId: String, itemId: String, positionMs: Long, nowMs: Long, pending: Boolean)

    /**
     * Sets the cached count of unplayed episodes under a season or series (null: not known), on a row that exists (a count
     * only means something on a row the server sent). With [pending] it also marks the row as holding an unconfirmed local change.
     */
    @Query("UPDATE user_data SET unplayedItemCount = :count, updatedAt = :nowMs, pendingSyncAt = CASE WHEN :pending THEN :nowMs ELSE pendingSyncAt END WHERE serverId = :serverId AND userId = :userId AND itemId = :itemId")
    internal abstract suspend fun setUnplayedCount(serverId: String, userId: String, itemId: String, count: Int?, nowMs: Long, pending: Boolean)

    /**
     * Puts a row back EXACTLY as the caller gives it: the undo of an optimistic action (the caller clears the marker; a restore never writes one). Not for data the server
     * sent (that is [writeServerUserData], which respects pending rows).
     */
    internal suspend fun restoreUserData(row: UserDataEntity) = upsertUserRows(listOf(row))

    /**
     * Marks an item watched or not for this user, creating the row if there is none. Watched sets the last-played time and
     * keeps at least one play; not watched resets the play count. [positionMs] is stored as given (a watched item has 0).
     * The percentage is cleared (it is derived from position and runtime). [nowMs] is stored as `updatedAt`. With [pending] the
     * same transaction also marks the row as holding a local change the server has not confirmed (`pendingSyncAt`), so a
     * refresh cannot undo it; without it an existing marker is left as it is (every setter works this way).
     */
    @Transaction
    open suspend fun setPlayed(serverId: String, userId: String, itemId: String, played: Boolean, positionMs: Long, nowMs: Long, pending: Boolean = false) {
        ensureUserData(serverId, userId, itemId, nowMs)
        updatePlayed(serverId, userId, itemId, played, positionMs, nowMs, pending)
    }

    /** Sets favourite for this user, creating the row if there is none. */
    @Transaction
    open suspend fun setFavourite(serverId: String, userId: String, itemId: String, favourite: Boolean, nowMs: Long, pending: Boolean = false) {
        ensureUserData(serverId, userId, itemId, nowMs)
        updateFavourite(serverId, userId, itemId, favourite, nowMs, pending)
    }

    /** Records where playback stopped for this user (0 clears it), creating the row if there is none. Does not change watched. */
    @Transaction
    open suspend fun setPosition(serverId: String, userId: String, itemId: String, positionMs: Long, nowMs: Long, pending: Boolean = false) {
        ensureUserData(serverId, userId, itemId, nowMs)
        updatePosition(serverId, userId, itemId, positionMs, nowMs, pending)
    }

    companion object {
        /** Well under SQLite's bound-variable limit on every Android version. */
        private const val ID_CHUNK = 500

        /**
         * The row has no offline play waiting for Milestone 5 (SPEC 3.2: a sync never undoes an offline watch before it is sent), so
         * its marker may be cleared by the start-up sweep or the idle clean-up.
         */
        internal const val NO_OFFLINE_PLAY =
            "NOT EXISTS (SELECT 1 FROM offline_plays op WHERE op.serverId = user_data.serverId AND op.userId = user_data.userId AND op.itemId = user_data.itemId)"

        private const val FROM_ITEMS_AND_USER_DATA =
            "FROM items i JOIN visible_items v ON v.serverId = i.serverId AND v.userId = :userId AND v.itemId = i.itemId " +
                "LEFT JOIN user_data u ON u.serverId = i.serverId AND u.userId = :userId AND u.itemId = i.itemId "

        private const val ONE_SQL = "SELECT $ROW_COLUMNS $FROM_ITEMS_AND_USER_DATA WHERE i.serverId = :serverId AND i.itemId = :itemId"

        /** Public so a test can ask SQLite for its query plan. */
        const val EPISODES_SQL =
            "SELECT $ROW_COLUMNS $FROM_ITEMS_AND_USER_DATA" +
                "WHERE i.serverId = :serverId AND i.seriesId = :seriesId AND i.kind = 'EPISODE' AND (:seasonId IS NULL OR i.seasonId = :seasonId) " +
                "ORDER BY CASE WHEN i.parentIndexNumber IS NULL THEN 1 WHEN i.parentIndexNumber = 0 THEN 2 ELSE 0 END, COALESCE(i.parentIndexNumber, 0), " +
                "i.indexNumber IS NULL, i.indexNumber, COALESCE(i.sortName, i.name), i.itemId"

        private const val SEASONS_SQL =
            "SELECT $ROW_COLUMNS $FROM_ITEMS_AND_USER_DATA" +
                "WHERE i.serverId = :serverId AND i.seriesId = :seriesId AND i.kind = 'SEASON' " +
                "ORDER BY CASE WHEN i.indexNumber IS NULL THEN 1 WHEN i.indexNumber = 0 THEN 2 ELSE 0 END, COALESCE(i.indexNumber, 0), " +
                "COALESCE(i.sortName, i.name), i.itemId"

        private const val SEARCH_SQL =
            "SELECT $ROW_COLUMNS $FROM_ITEMS_AND_USER_DATA" +
                "WHERE i.serverId = :serverId AND i.kind IN (:kinds) AND i.nameKey LIKE :pattern ESCAPE '\\' ORDER BY i.nameKey, i.itemId LIMIT :limit"

        private const val SEARCH_ALL_KINDS_SQL =
            "SELECT $ROW_COLUMNS $FROM_ITEMS_AND_USER_DATA" +
                "WHERE i.serverId = :serverId AND i.nameKey LIKE :pattern ESCAPE '\\' ORDER BY i.nameKey, i.itemId LIMIT :limit"
    }
}
