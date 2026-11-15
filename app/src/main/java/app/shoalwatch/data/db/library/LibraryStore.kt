package app.shoalwatch.data.db.library

import androidx.room.withTransaction
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.LibraryView
import app.shoalwatch.data.servers.Session
import java.time.Duration
import java.time.Instant

/** What to keep. A list not refreshed within [listMaxAge], or an unprotected item older than [itemMaxAge], goes; at most [maxUnpinnedItems] unpinned items stay per server. */
data class EvictionPolicy(
    val listMaxAge: Duration = Duration.ofDays(30),
    val itemMaxAge: Duration = Duration.ofDays(30),
    val maxUnpinnedItems: Int = 20_000,
)

/** What [LibraryStore.writePage] did. */
enum class PageResult { Written, Stale }

/**
 * The writes that span more than one table, each one transaction. Screens never call this; they observe the DAOs.
 * Reads go through `db.itemDao()`, `db.listDao()` and `db.viewDao()`.
 */
class LibraryStore(private val db: AppDatabase) {
    private val items = db.itemDao()
    private val lists = db.listDao()
    private val views = db.viewDao()
    private val maintenance = db.libraryMaintenanceDao()

    /**
     * Stores one page of a list as ONE transaction: upserts the items (see [ItemDao.upsertItems] for what is kept);
     * upserts the user data of items that came with some (an item with none leaves its stored user data untouched);
     * replaces the list's entries from [start] on (`start == 0` replaces the whole list, a refresh); and records the list's
     * state. [total] is what the server said the list holds (null: no usable count). [nextStartIndex], [pagesLoaded] and
     * [endReached] are the paging state to resume from (see [ListStateEntity]); the defaults describe a list read in one go.
     *
     * Positions stay contiguous from 0, so a page must begin at or before the end of what is stored (checked against the
     * real positions, which are made contiguous first if something wrote around this class). A page that begins beyond
     * the end means the list changed underneath the caller (a refresh finished first): nothing at all is written and
     * the result is [PageResult.Stale]. So is a page given [basedOn] (the state the caller read before it fetched the page)
     * when the stored state is no longer that one (same `refreshedAt` and `loadedCount`): the page belongs to a list that has since been replaced.
     *
     * @throws IllegalArgumentException for a negative [start], or a [listKey] that is empty or longer than 200 characters (programming errors).
     */
    suspend fun writePage(
        s: Session,
        listKey: String,
        start: Int,
        page: List<ItemWithUserData>,
        total: Int?,
        now: Instant,
        nextStartIndex: Int = start + page.size,
        pagesLoaded: Int = 1,
        endReached: Boolean = true,
        basedOn: ListStateEntity? = null,
    ): PageResult {
        require(start >= 0) { "A page cannot start before the first item." }
        require(listKey.isNotEmpty() && listKey.length <= ListEntryEntity.MAX_LIST_KEY) { "A list key is 1 to 200 characters." }
        return db.withTransaction {
            if (basedOn != null) {
                val now = lists.getState(s.serverId, s.userId, listKey)
                if (now == null || now.refreshedAt != basedOn.refreshedAt || now.loadedCount != basedOn.loadedCount) return@withTransaction PageResult.Stale
            }
            val stored = if (start == 0) 0 else repacked(s, listKey)
            if (start > stored) return@withTransaction PageResult.Stale
            writeItems(s, page, now)
            lists.deleteEntriesFrom(s.serverId, s.userId, listKey, start)
            lists.insertEntries(page.mapIndexed { i, row -> ListEntryEntity(s.serverId, s.userId, listKey, start + i, row.item.id) })
            val loaded = start + page.size
            lists.upsertState(ListStateEntity(s.serverId, s.userId, listKey, total?.coerceAtLeast(loaded), loaded, nextStartIndex, pagesLoaded, endReached, now.toEpochMilli()))
            PageResult.Written
        }
    }

    /** How many entries the list has, after making its positions contiguous from 0 if they were not (they always are unless something wrote around this class). */
    private suspend fun repacked(s: Session, listKey: String): Int {
        val count = lists.countEntries(s.serverId, s.userId, listKey)
        if ((lists.maxPosition(s.serverId, s.userId, listKey) ?: -1) + 1 != count) {
            val all = lists.entriesFrom(s.serverId, s.userId, listKey, Int.MIN_VALUE)
            lists.deleteEntriesFrom(s.serverId, s.userId, listKey, Int.MIN_VALUE)
            lists.insertEntries(all.mapIndexed { i, e -> e.copy(position = i) })
        }
        return count
    }

    /**
     * Removes every occurrence of [itemId] from the list, closes the gap (later entries move down, so positions stay
     * contiguous from 0) and lowers `loadedCount` and a known `total` (never below `loadedCount`). `nextStartIndex` is a
     * position on the SERVER and does not change. One transaction. False when the item is not in the list.
     */
    suspend fun removeEntry(s: Session, listKey: String, itemId: String): Boolean = db.withTransaction {
        repacked(s, listKey)
        val first = lists.firstPositionOf(s.serverId, s.userId, listKey, itemId) ?: return@withTransaction false
        val tail = lists.entriesFrom(s.serverId, s.userId, listKey, first)
        val kept = tail.filter { it.itemId != itemId }
        lists.deleteEntriesFrom(s.serverId, s.userId, listKey, first)
        lists.insertEntries(kept.mapIndexed { i, e -> e.copy(position = first + i) })
        resize(s, listKey, -(tail.size - kept.size))
        true
    }

    /**
     * Puts [itemId] at [position] (0 up to the current length), moving later entries up: the undo of [removeEntry] for
     * an optimistic change. One transaction. False when the list has never been loaded or [position] is out of range.
     */
    suspend fun insertEntryAt(s: Session, listKey: String, position: Int, itemId: String): Boolean = db.withTransaction {
        if (lists.getState(s.serverId, s.userId, listKey) == null) return@withTransaction false
        if (position < 0 || position > repacked(s, listKey)) return@withTransaction false
        val tail = lists.entriesFrom(s.serverId, s.userId, listKey, position)
        lists.deleteEntriesFrom(s.serverId, s.userId, listKey, position)
        lists.insertEntries(listOf(ListEntryEntity(s.serverId, s.userId, listKey, position, itemId)) + tail.mapIndexed { i, e -> e.copy(position = position + 1 + i) })
        resize(s, listKey, 1)
        true
    }

    /** Moves `loadedCount` (and a known `total`) by [delta]; the server's paging position is left alone. */
    private suspend fun resize(s: Session, listKey: String, delta: Int) {
        val state = lists.getState(s.serverId, s.userId, listKey) ?: return
        val loaded = (state.loadedCount + delta).coerceAtLeast(0)
        lists.upsertState(state.copy(loadedCount = loaded, total = state.total?.plus(delta)?.coerceAtLeast(loaded)))
    }

    /**
     * Stores items (and the user data that came with them) without touching any list, e.g. a detail fetch. Same rules as
     * [writePage]: [ItemDao.upsertItems] for the items (a list write keeps a stored `overview` and `childCount` when the
     * incoming value is null; an [authoritative] write, i.e. a detail fetch, does not), and for user data the server
     * provided, [ItemDao.writeServerUserData]: a row with an unconfirmed local change (`pendingSyncAt`) is ignored. In the same
     * transaction every item written is recorded as shown to this user by the server ([VisibleItemEntity]).
     */
    suspend fun writeItems(s: Session, rows: List<ItemWithUserData>, now: Instant, authoritative: Boolean = false) {
        db.withTransaction {
            items.upsertItems(s.serverId, rows.map { it.item.toEntity(s.serverId, now) }, authoritative)
            items.markVisible(s.serverId, s.userId, rows.map { it.item.id }, now.toEpochMilli())
            items.writeServerUserData(rows.mapNotNull { r -> r.userData?.toEntity(s.serverId, s.userId, r.item.id, now) })
        }
    }

    /**
     * The server refused the item for this user: it stops existing for them. Their `visible_items` record goes and the item
     * leaves each of their lists (later entries move up, counts drop), in one transaction. Other users, and the shared item row, are untouched.
     * A later server return of the item to this user makes it visible again ([writeItems]).
     */
    suspend fun revokeVisibility(s: Session, itemId: String) = db.withTransaction {
        items.deleteVisible(s.serverId, s.userId, itemId)
        lists.keysContaining(s.serverId, s.userId, itemId).forEach { removeEntry(s, it, itemId) }
    }

    /** Marks this user's row for the item as holding a local change the server has not confirmed. False if there is no row (set the value first). */
    suspend fun markPending(s: Session, itemId: String, now: Instant): Boolean = items.markPending(s.serverId, s.userId, itemId, now.toEpochMilli()) > 0

    /** The server has confirmed the change: the next refresh may overwrite the row again. The local value is kept. */
    suspend fun clearPending(s: Session, itemId: String) = items.clearPending(s.serverId, s.userId, itemId)

    /** Deletes this user's row for the item (even if pending), to roll back an optimistic action on a row that did not exist. */
    suspend fun deleteUserData(s: Session, itemId: String) = items.deleteUserData(s.serverId, s.userId, itemId)

    // ---- what the tidy-up actions (PlaystateLocal) read and write: the one place their rules are applied

    /** Runs [block] as one transaction (several store calls, or reads that must agree with the writes that follow). */
    suspend fun <T> inTransaction(block: suspend () -> T): T = db.withTransaction { block() }

    /** The item as this user may see it (a record that the server showed it to them), or null. */
    suspend fun visibleItem(s: Session, itemId: String): ItemRow? = items.getItem(s.serverId, s.userId, itemId)

    suspend fun userData(s: Session, itemId: String): UserDataEntity? = items.getUserData(s.serverId, s.userId, itemId)

    suspend fun entryIds(s: Session, listKey: String): List<String> = lists.entryIds(s.serverId, s.userId, listKey)

    /** This user's saved list, visible items only. */
    suspend fun savedList(s: Session, listKey: String, limit: Int): List<ItemRow> = lists.getList(s.serverId, s.userId, listKey, limit)

    /** The saved episodes of a series (or one season) this user may see. */
    suspend fun savedEpisodes(s: Session, seriesId: String, seasonId: String?): List<ItemRow> = items.getEpisodes(s.serverId, s.userId, seriesId, seasonId)

    /** An optimistic watched/unwatched change: position 0, row created if missing, marker set (see [ItemDao.setPlayed]). */
    suspend fun setPlayedPending(s: Session, itemId: String, played: Boolean, nowMs: Long) = items.setPlayed(s.serverId, s.userId, itemId, played, 0, nowMs, pending = true)

    suspend fun setFavouritePending(s: Session, itemId: String, favourite: Boolean, nowMs: Long) = items.setFavourite(s.serverId, s.userId, itemId, favourite, nowMs, pending = true)

    /** An optimistic "start again": position 0, `played` untouched, marker set. */
    suspend fun clearPositionPending(s: Session, itemId: String, nowMs: Long) = items.setPosition(s.serverId, s.userId, itemId, 0, nowMs, pending = true)

    suspend fun setUnplayedCountPending(s: Session, itemId: String, count: Int?, nowMs: Long) = items.setUnplayedCount(s.serverId, s.userId, itemId, count, nowMs, pending = true)

    /** The badge back without a marker (rolling back a change to it). */
    suspend fun setUnplayedCount(s: Session, itemId: String, count: Int?, nowMs: Long) = items.setUnplayedCount(s.serverId, s.userId, itemId, count, nowMs, pending = false)

    /** Puts a row back exactly as it was (see [ItemDao.restoreUserData]): the undo of an optimistic change only. */
    suspend fun restoreUserData(row: UserDataEntity) = items.restoreUserData(row)

    /**
     * The start-up sweep: clears EVERY pending marker (all servers and users; the local values stay), and makes every list of
     * each user who had one [ageByMs] older so it refreshes promptly and the server's copy decides. One transaction.
     * Returns how many markers were cleared. Milestone 5 replaces this with outbox replay.
     */
    suspend fun clearAllPending(ageByMs: Long): Int = db.withTransaction {
        val pending = items.pendingRows()
        if (pending.isEmpty()) return@withTransaction 0
        items.clearAllPending()
        pending.map { it.serverId to it.userId }.distinct().forEach { (server, user) -> lists.ageLists(server, user, ageByMs) }
        pending.size
    }

    /** Makes this user's stored libraries exactly [libraries], in order. */
    suspend fun replaceViews(s: Session, libraries: List<LibraryView>) =
        views.replaceViews(s.serverId, s.userId, libraries.mapIndexed { i, v -> v.toEntity(s.serverId, s.userId, i) })

    suspend fun serverIds(): List<String> = maintenance.serverIds()

    /** Eviction for one server (one transaction). See [LibraryMaintenanceDao.evict]. */
    suspend fun evictServer(serverId: String, now: Instant, policy: EvictionPolicy = EvictionPolicy()): EvictionCounts =
        maintenance.evict(serverId, now.minus(policy.listMaxAge).toEpochMilli(), now.minus(policy.itemMaxAge).toEpochMilli(), policy.maxUnpinnedItems)

    /** Eviction for every server, one transaction each, and the totals. */
    suspend fun evict(now: Instant, policy: EvictionPolicy = EvictionPolicy()): EvictionCounts {
        val each = serverIds().map { evictServer(it, now, policy) }
        return EvictionCounts(each.sumOf { it.listEntries }, each.sumOf { it.listStates }, each.sumOf { it.items }, each.sumOf { it.userData }, each.sumOf { it.visibility })
    }
}
