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
class LibraryStore(
    private val db: AppDatabase,
    /** What was changed locally: a server answer asked for before a change never overwrites it (see [LocalChangeLog]). The one instance, shared. */
    private val changes: LocalChangeLog,
) {
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
        requestedAtSeq: Long,
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
            writeItems(s, page, now, requestedAtSeq = requestedAtSeq)
            // An item a local action took out of this list after this page was asked for stays out: the page is older than that.
            // So does one an UNSENT change took out (its outbox row's departure mark), whenever the page was asked for: the server has not heard yet.
            val unsent = db.outboxDao().leftList(s.serverId, s.userId, listKey).toSet()
            val entries = page.filterNot {
                it.item.id in unsent || (changes.removedFrom(s, it.item.id, listKey) && changes.newerThan(s, it.item.id, requestedAtSeq))
            }
            lists.deleteEntriesFrom(s.serverId, s.userId, listKey, start)
            lists.insertEntries(entries.mapIndexed { i, row -> ListEntryEntity(s.serverId, s.userId, listKey, start + i, row.item.id) })
            val loaded = start + entries.size
            val knownTotal = total?.minus(page.size - entries.size)
            lists.upsertState(ListStateEntity(s.serverId, s.userId, listKey, knownTotal?.coerceAtLeast(loaded), loaded, nextStartIndex, pagesLoaded, endReached, now.toEpochMilli()))
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
    suspend fun writeItems(s: Session, rows: List<ItemWithUserData>, now: Instant, authoritative: Boolean = false, requestedAtSeq: Long) {
        db.withTransaction {
            items.upsertItems(s.serverId, rows.map { it.item.toEntity(s.serverId, now) }, authoritative)
            items.markVisible(s.serverId, s.userId, rows.map { it.item.id }, now.toEpochMilli())
            // The one way server user data is written. Skipped, besides pending rows (in the DAO): any item changed locally at or
            // after the moment this answer was asked for. The shared item row and visibility above are still written.
            items.writeServerUserData(rows.filterNot { changes.newerThan(s, it.item.id, requestedAtSeq) }.mapNotNull { r -> r.userData?.toEntity(s.serverId, s.userId, r.item.id, now) })
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
    suspend fun deleteUserData(s: Session, itemId: String) {
        items.deleteUserData(s.serverId, s.userId, itemId)
        changes.record(s, itemId)
    }

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
    suspend fun setPlayedPending(s: Session, itemId: String, played: Boolean, nowMs: Long) {
        changes.record(s, itemId)
        items.setPlayed(s.serverId, s.userId, itemId, played, 0, nowMs, pending = true)
    }

    suspend fun setFavouritePending(s: Session, itemId: String, favourite: Boolean, nowMs: Long) {
        changes.record(s, itemId)
        items.setFavourite(s.serverId, s.userId, itemId, favourite, nowMs, pending = true)
    }

    /** An optimistic "start again": position 0, `played` untouched, marker set. */
    suspend fun clearPositionPending(s: Session, itemId: String, nowMs: Long) {
        changes.record(s, itemId)
        items.setPosition(s.serverId, s.userId, itemId, 0, nowMs, pending = true)
    }

    /** An optimistic "stopped here" from the player: position as given, `played` untouched, marker set. */
    suspend fun setPositionPending(s: Session, itemId: String, positionMs: Long, nowMs: Long) {
        changes.record(s, itemId)
        items.setPosition(s.serverId, s.userId, itemId, positionMs, nowMs, pending = true)
    }

    suspend fun setUnplayedCountPending(s: Session, itemId: String, count: Int?, nowMs: Long) {
        changes.record(s, itemId)
        items.setUnplayedCount(s.serverId, s.userId, itemId, count, nowMs, pending = true)
    }

    /** The badge back without a marker (rolling back a change to it). */
    suspend fun setUnplayedCount(s: Session, itemId: String, count: Int?, nowMs: Long) {
        changes.record(s, itemId)
        items.setUnplayedCount(s.serverId, s.userId, itemId, count, nowMs, pending = false)
    }

    /** Puts a row back exactly as it was (see [ItemDao.restoreUserData]): the undo of an optimistic change only. */
    suspend fun restoreUserData(row: UserDataEntity) {
        changes.record(Session(row.serverId, row.userId), row.itemId)
        items.restoreUserData(row)
    }

    /** Takes the item out of a list because of an action, and remembers it, so an older server answer cannot put it back (see [LocalChangeLog.removedFrom]). */
    suspend fun removeEntryForAction(s: Session, listKey: String, itemId: String): Boolean {
        changes.record(s, itemId, removedFrom = listKey)
        return removeEntry(s, listKey, itemId)
    }

    /** The undo of [removeEntryForAction]: the entry back at its position, and the server's own answers about that list are authoritative for it again. */
    suspend fun restoreEntry(s: Session, listKey: String, position: Int, itemId: String): Boolean {
        changes.forgetRemoval(s, itemId, listKey)
        return insertEntryAt(s, listKey, position, itemId)
    }

    /** The stamp for a request, read immediately BEFORE it is sent; pass it to [writePage] or [writeItems] with that request's answer. */
    fun requestStamp(): Long = changes.stamp()

    /** A change the action has just had confirmed (or rolled back) is a change too: every request sent before this moment is older than it. */
    fun noteChanged(s: Session, itemId: String, removedFrom: String? = null) = changes.record(s, itemId, removedFrom)

    /** The entry was put back by a rollback: the server's own answers about that list are authoritative for it again. */
    fun forgetRemoval(s: Session, itemId: String, listKey: String) = changes.forgetRemoval(s, itemId, listKey)

    /** Clears the markers of one user (the local values stay): used when that user has no action running, so none can be live. Returns how many were cleared. */
    suspend fun clearPendingFor(s: Session): Int = items.clearPendingFor(s.serverId, s.userId)

    /** One row with an unconfirmed local change. */
    data class PendingKey(val serverId: String, val userId: String, val itemId: String)

    /** Every row that has a marker right now (for the start-up sweep's snapshot). */
    suspend fun pendingKeys(): Set<PendingKey> = items.pendingRows().map { PendingKey(it.serverId, it.userId, it.itemId) }.toSet()

    /**
     * The start-up sweep: clears the markers of exactly the rows in [snapshot] that still have one and that this process has
     * not touched since (a marker on a touched row may belong to a live action). The local values stay. Each user who really
     * had a leftover cleared has their lists made [ageByMs] older, so they refresh promptly and the server's copy decides.
     * One transaction. Returns how many markers were cleared. Milestone 5 replaces this with outbox replay.
     */
    suspend fun clearLeftovers(snapshot: Set<PendingKey>, ageByMs: Long): Int = db.withTransaction {
        val clear = items.pendingRows()
            .map { PendingKey(it.serverId, it.userId, it.itemId) }
            .filter { it in snapshot && !changes.touched(Session(it.serverId, it.userId), it.itemId) }
        clear.forEach { items.clearPending(it.serverId, it.userId, it.itemId) }
        clear.map { it.serverId to it.userId }.distinct().forEach { (server, user) -> lists.ageLists(server, user, ageByMs) }
        clear.size
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
