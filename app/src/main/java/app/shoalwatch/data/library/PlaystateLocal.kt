package app.shoalwatch.data.library

import androidx.room.withTransaction
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.db.library.ItemEntity
import app.shoalwatch.data.db.library.ItemRow
import app.shoalwatch.data.db.library.LibraryStore
import app.shoalwatch.data.db.library.UserDataEntity
import app.shoalwatch.data.servers.Session

/** A list entry an action took out, and where it was. */
internal data class RemovedEntry(val listKey: String, val itemId: String, val position: Int)

/**
 * What one action changed locally, as it is changed: the `user_data` row of every item touched as it was before (null: it
 * did not exist), the list entries taken out, and the lists the server recomputes (to refresh once it has agreed).
 * Everything needed to put the cache back exactly as it was ([PlaystateLocal.restore]) or to clear the pending markers.
 */
internal class Touched {
    val rows = LinkedHashMap<String, UserDataEntity?>()
    val removed = ArrayList<RemovedEntry>()
    val recompute = LinkedHashSet<Recompute>()
}

/**
 * The local half of every tidy-up action: the optimistic change (always with the pending marker, always recorded in
 * [Touched] first) and its exact undo. Callers run it inside the library write gate and one transaction. Every row is for
 * the session's user only; item rows are looked up through the per-user visibility rule.
 */
internal class PlaystateLocal(private val db: AppDatabase, private val clock: () -> Long) {
    private val items = db.itemDao()
    private val lists = db.listDao()
    private val store = LibraryStore(db)

    suspend fun <T> transaction(block: suspend () -> T): T = db.withTransaction { block() }

    /** The item as this user may see it, or null (never shown to them, or not saved). */
    suspend fun visibleItem(s: Session, itemId: String): ItemRow? = items.getItem(s.serverId, s.userId, itemId)

    private suspend fun Touched.remember(s: Session, itemId: String) {
        if (itemId !in rows) rows[itemId] = items.getUserData(s.serverId, s.userId, itemId)
    }

    /** Takes the item out of `resume` (every occurrence), remembering where it was. */
    private suspend fun Touched.dropFromResume(s: Session, itemId: String) {
        val position = lists.entryIds(s.serverId, s.userId, ListKey.RESUME).indexOf(itemId)
        if (position < 0) return
        removed += RemovedEntry(ListKey.RESUME, itemId, position)
        store.removeEntry(s, ListKey.RESUME, itemId)
    }

    // ---- the actions

    /** Position back to the start, `played` untouched, and out of Continue Watching. Next Up is not affected by this on the server. */
    suspend fun clearResume(s: Session, itemId: String): Touched = Touched().also { t ->
        t.remember(s, itemId)
        items.setPosition(s.serverId, s.userId, itemId, 0, clock(), pending = true)
        t.dropFromResume(s, itemId)
        t.recompute += Recompute.Resume
    }

    suspend fun favourite(s: Session, itemId: String, favourite: Boolean): Touched = Touched().also { t ->
        t.remember(s, itemId)
        items.setFavourite(s.serverId, s.userId, itemId, favourite, clock(), pending = true)
    }

    /**
     * Watched or not, as the server will record it. A film or episode changes its own row (an episode also moves the
     * badges of its season and series); a season or series changes its own row and every saved episode and season under
     * it; any other kind changes only its own row.
     */
    suspend fun played(s: Session, item: ItemRow, played: Boolean): Touched = Touched().also { t ->
        val e = item.item
        when (e.kind) {
            ItemKind.EPISODE -> episode(s, t, item, played)
            ItemKind.SEASON -> season(s, t, e, played)
            ItemKind.SERIES -> series(s, t, e, played)
            else -> markOne(s, t, e.itemId, played)
        }
        t.recompute += listOf(Recompute.Resume, Recompute.NextUp)
    }

    private suspend fun episode(s: Session, t: Touched, item: ItemRow, played: Boolean) {
        markOne(s, t, item.item.itemId, played)
        if ((item.userData?.played == true) == played) return // nothing changed, so no badge moves
        listOfNotNull(item.item.seasonId, item.item.seriesId).forEach { parent -> adjustUnplayedCount(s, t, parent, if (played) -1 else 1) }
    }

    /** The server marks every episode; locally that is every episode this user was shown and the phone has saved. The series badge comes back with the refresh of the series. */
    private suspend fun season(s: Session, t: Touched, season: ItemEntity, played: Boolean) {
        markContainer(s, t, season.itemId, played)
        val seriesId = season.seriesId ?: return
        items.getEpisodes(s.serverId, s.userId, seriesId, season.itemId).forEach { markOne(s, t, it.item.itemId, played) }
        t.recompute += listOf(Recompute.Item(seriesId), Recompute.Seasons(seriesId), Recompute.Episodes(seriesId, season.itemId))
    }

    private suspend fun series(s: Session, t: Touched, series: ItemEntity, played: Boolean) {
        markContainer(s, t, series.itemId, played)
        val seasons = lists.getList(s.serverId, s.userId, ListKey.seasons(series.itemId), MAX_CHILDREN).filter { it.item.kind == ItemKind.SEASON }
        seasons.forEach { markContainer(s, t, it.item.itemId, played) }
        val episodes = items.getEpisodes(s.serverId, s.userId, series.itemId, null)
        episodes.forEach { markOne(s, t, it.item.itemId, played) }
        val seasonIds = (seasons.map { it.item.itemId } + episodes.mapNotNull { it.item.seasonId }).distinct()
        t.recompute += listOf(Recompute.Item(series.itemId), Recompute.Seasons(series.itemId)) + seasonIds.map { Recompute.Episodes(series.itemId, it) }
    }

    /**
     * A season or series row marked as a whole: watched means nothing is left unplayed (0); unwatched means all of it is, but
     * the server's count is not something to guess at from a partly saved list, so the badge is left unknown (null) until the refresh.
     */
    private suspend fun markContainer(s: Session, t: Touched, itemId: String, played: Boolean) {
        markOne(s, t, itemId, played)
        items.setUnplayedCount(s.serverId, s.userId, itemId, if (played) 0 else null, clock(), pending = true)
    }

    private suspend fun markOne(s: Session, t: Touched, itemId: String, played: Boolean) {
        t.remember(s, itemId)
        // The store's own setter: watched sets position 0, last played and at least one play; unwatched resets the play count and keeps the date.
        items.setPlayed(s.serverId, s.userId, itemId, played, 0, clock(), pending = true)
        t.dropFromResume(s, itemId) // position 0 either way, so it is no longer something to resume
    }

    /** A season or series badge moves by one when an episode under it is marked; never below 0, and only where the server sent a count. */
    private suspend fun adjustUnplayedCount(s: Session, t: Touched, itemId: String, delta: Int) {
        val count = items.getUserData(s.serverId, s.userId, itemId)?.unplayedItemCount ?: return
        t.remember(s, itemId)
        items.setUnplayedCount(s.serverId, s.userId, itemId, (count + delta).coerceAtLeast(0), clock(), pending = true)
    }

    // ---- after the server has answered

    /** The server agrees: the next refresh may overwrite these rows again (the local value stays). */
    suspend fun clearPending(s: Session, itemIds: Collection<String>) = itemIds.forEach { items.clearPending(s.serverId, s.userId, it) }

    /**
     * Puts every row back exactly as it was (a row that did not exist is deleted, and its marker goes with it) and every
     * removed list entry back at its old position. An entry a refresh has put back in the meantime is left where it is.
     * Entries go back in ascending position order, so each lands where it was.
     */
    suspend fun restore(s: Session, t: Touched) {
        for ((itemId, before) in t.rows) {
            if (before == null) store.deleteUserData(s, itemId) else items.restoreUserData(before)
        }
        for (e in t.removed.sortedBy { it.position }) {
            val now = lists.entryIds(s.serverId, s.userId, e.listKey)
            if (e.itemId !in now) store.insertEntryAt(s, e.listKey, e.position.coerceAtMost(now.size), e.itemId)
        }
    }

    private companion object {
        const val MAX_CHILDREN = 10_000
    }
}
