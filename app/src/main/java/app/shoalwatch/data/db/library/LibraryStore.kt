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
     * Positions stay contiguous from 0, so a page must begin at or before the end of what is stored.
     *
     * @throws IllegalArgumentException for a negative [start], a start beyond the stored entries, or a [listKey] that is empty or longer than 200 characters.
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
    ) {
        require(start >= 0) { "A page cannot start before the first item." }
        require(listKey.isNotEmpty() && listKey.length <= ListEntryEntity.MAX_LIST_KEY) { "A list key is 1 to 200 characters." }
        db.withTransaction {
            require(start == 0 || start <= lists.countEntries(s.serverId, s.userId, listKey)) { "A page must continue the list it extends." }
            writeItems(s, page, now)
            lists.deleteEntriesFrom(s.serverId, s.userId, listKey, start)
            lists.insertEntries(page.mapIndexed { i, row -> ListEntryEntity(s.serverId, s.userId, listKey, start + i, row.item.id) })
            val loaded = start + page.size
            lists.upsertState(ListStateEntity(s.serverId, s.userId, listKey, total?.coerceAtLeast(loaded), loaded, nextStartIndex, pagesLoaded, endReached, now.toEpochMilli()))
        }
    }

    /** Stores items (and the user data that came with them) without touching any list, e.g. a detail fetch. Same rules as [writePage]. */
    suspend fun writeItems(s: Session, rows: List<ItemWithUserData>, now: Instant) {
        db.withTransaction {
            items.upsertItems(s.serverId, rows.map { it.item.toEntity(s.serverId, now) })
            items.upsertUserData(rows.mapNotNull { r -> r.userData?.toEntity(s.serverId, s.userId, r.item.id, now) })
        }
    }

    /** Makes this user's stored libraries exactly [libraries], in order. */
    suspend fun replaceViews(s: Session, libraries: List<LibraryView>) =
        views.replaceViews(s.serverId, s.userId, libraries.mapIndexed { i, v -> v.toEntity(s.serverId, s.userId, i) })

    /** Runs [ItemDao]-level eviction for every server (one transaction each) and returns the totals. See [LibraryMaintenanceDao.evict]. */
    suspend fun evict(now: Instant, policy: EvictionPolicy = EvictionPolicy()): EvictionCounts {
        val listCutoff = now.minus(policy.listMaxAge).toEpochMilli()
        val itemCutoff = now.minus(policy.itemMaxAge).toEpochMilli()
        val each = maintenance.serverIds().map { maintenance.evict(it, listCutoff, itemCutoff, policy.maxUnpinnedItems) }
        return EvictionCounts(each.sumOf { it.listEntries }, each.sumOf { it.listStates }, each.sumOf { it.items }, each.sumOf { it.userData })
    }
}
