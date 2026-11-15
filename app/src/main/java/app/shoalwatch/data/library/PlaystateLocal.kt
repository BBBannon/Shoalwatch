package app.shoalwatch.data.library

import app.shoalwatch.data.db.library.ItemEntity
import app.shoalwatch.data.db.library.ItemRow
import app.shoalwatch.data.db.library.LibraryStore
import app.shoalwatch.data.db.library.UserDataEntity
import app.shoalwatch.data.servers.Session

/** A list entry an action took out, and where it was in the list AS IT WAS WHEN THIS ENTRY WAS TAKEN OUT (earlier removals already gone). */
internal data class RemovedEntry(val listKey: String, val itemId: String, val position: Int)

/**
 * What one action changed locally, as it is changed:
 *  - [rows]: the `user_data` row of every item whose watched state, position or favourite was set, as it was before (null: it did not exist);
 *  - [badges]: the amount each season or series `unplayedItemCount` was moved by (not a snapshot: the badge is shared by sibling episodes);
 *  - [removed]: the list entries taken out, in the order they were taken out;
 *  - [recompute]: the lists the server recomputes, to refresh once it has agreed.
 * Everything needed to put the cache back ([PlaystateLocal.restore]) or to clear the pending markers ([markedIds]).
 */
internal class Touched {
    val rows = LinkedHashMap<String, UserDataEntity?>()
    val badges = LinkedHashMap<String, Int>()
    val removed = ArrayList<RemovedEntry>()
    val recompute = LinkedHashSet<Recompute>()

    /** Every row this action marked pending. */
    val markedIds: Set<String> get() = rows.keys + badges.keys
}

/**
 * The local half of every tidy-up action: the optimistic change (always with the pending marker, always recorded in
 * [Touched] first) and its exact undo. Everything goes through [LibraryStore], so visibility and the pending rules are
 * applied in one place. Callers run it inside the library write gate and one store transaction, one action at a time per
 * user. Every row is for the session's user only; item rows are looked up through the per-user visibility rule.
 */
internal class PlaystateLocal(private val store: LibraryStore, private val clock: () -> Long) {
    suspend fun <T> transaction(block: suspend () -> T): T = store.inTransaction(block)

    /** The item as this user may see it, or null (never shown to them, or not saved). */
    suspend fun visibleItem(s: Session, itemId: String): ItemRow? = store.visibleItem(s, itemId)

    private suspend fun Touched.remember(s: Session, itemId: String) {
        if (itemId !in rows) rows[itemId] = store.userData(s, itemId)
    }

    /** Takes the item out of `resume` (a list holds an id once, so the one entry), remembering where it was. */
    private suspend fun Touched.dropFromResume(s: Session, itemId: String) {
        val position = store.entryIds(s, ListKey.RESUME).indexOf(itemId)
        if (position < 0) return
        removed += RemovedEntry(ListKey.RESUME, itemId, position)
        store.removeEntryForAction(s, ListKey.RESUME, itemId)
    }

    // ---- the actions

    /** Position back to the start, `played` untouched, and out of Continue Watching. Next Up is not affected by this on the server. */
    suspend fun clearResume(s: Session, itemId: String): Touched = Touched().also { t ->
        t.remember(s, itemId)
        store.clearPositionPending(s, itemId, clock())
        t.dropFromResume(s, itemId)
        t.recompute += Recompute.Resume
    }

    suspend fun favourite(s: Session, itemId: String, favourite: Boolean): Touched = Touched().also { t ->
        t.remember(s, itemId)
        store.setFavouritePending(s, itemId, favourite, clock())
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
        listOfNotNull(item.item.seasonId, item.item.seriesId).forEach { parent -> moveBadge(s, t, parent, if (played) -1 else 1) }
    }

    /** The server marks every episode; locally that is every episode this user was shown and the phone has saved. The series badge comes back with the refresh of the series. */
    private suspend fun season(s: Session, t: Touched, season: ItemEntity, played: Boolean) {
        markContainer(s, t, season.itemId, played)
        val seriesId = season.seriesId ?: return
        store.savedEpisodes(s, seriesId, season.itemId).forEach { markOne(s, t, it.item.itemId, played) }
        t.recompute += listOf(Recompute.Item(seriesId), Recompute.Seasons(seriesId), Recompute.Episodes(seriesId, season.itemId))
    }

    private suspend fun series(s: Session, t: Touched, series: ItemEntity, played: Boolean) {
        markContainer(s, t, series.itemId, played)
        val seasons = store.savedList(s, ListKey.seasons(series.itemId), MAX_CHILDREN).filter { it.item.kind == ItemKind.SEASON }
        seasons.forEach { markContainer(s, t, it.item.itemId, played) }
        val episodes = store.savedEpisodes(s, series.itemId, null)
        episodes.forEach { markOne(s, t, it.item.itemId, played) }
        val seasonIds = (seasons.map { it.item.itemId } + episodes.mapNotNull { it.item.seasonId }).distinct()
        t.recompute += listOf(Recompute.Item(series.itemId), Recompute.Seasons(series.itemId)) + seasonIds.map { Recompute.Episodes(series.itemId, it) }
    }

    private suspend fun markOne(s: Session, t: Touched, itemId: String, played: Boolean) {
        t.remember(s, itemId)
        // The store's own setter: watched sets position 0, last played and at least one play; unwatched resets the play count and keeps the date.
        store.setPlayedPending(s, itemId, played, clock())
        t.dropFromResume(s, itemId) // position 0 either way, so it is no longer something to resume
    }

    /**
     * A season or series row marked as a whole: watched means nothing is left unplayed (0); unwatched means all of it is, but
     * the server's count is not something to guess at from a partly saved list, so the badge is left unknown (null) until the refresh.
     */
    private suspend fun markContainer(s: Session, t: Touched, itemId: String, played: Boolean) {
        markOne(s, t, itemId, played)
        store.setUnplayedCountPending(s, itemId, if (played) 0 else null, clock())
    }

    /**
     * A season or series badge moves by one when an episode under it is marked; never below 0, and only where the server sent
     * a count. What it really moved by is recorded, so the undo reverses that on whatever the row holds by then (sibling
     * episodes share it) instead of writing an old copy back.
     */
    private suspend fun moveBadge(s: Session, t: Touched, itemId: String, delta: Int) {
        val count = store.userData(s, itemId)?.unplayedItemCount ?: return
        val next = (count + delta).coerceAtLeast(0)
        store.setUnplayedCountPending(s, itemId, next, clock())
        t.badges.merge(itemId, next - count, Int::plus)
    }

    // ---- after the server has answered

    /** This user has no action running (the queue is idle), so no marker left on their rows can be live: clear them. */
    suspend fun clearLeftovers(s: Session) = store.clearPendingFor(s)

    /** The server agrees: the next refresh may overwrite these rows again (the local value stays). */
    suspend fun clearPending(s: Session, itemIds: Collection<String>) = itemIds.forEach { store.clearPending(s, it) }

    /**
     * Puts the cache back as it was. Rows set as a whole go back exactly (a row that did not exist is deleted) and NEVER with
     * a marker: one action runs at a time per user, so no marker left on a row belongs to anyone else. A badge is moved back by
     * what this action moved it by. Removed list entries go back in REVERSE order of removal (each was recorded after the
     * earlier ones were gone, so undoing newest first puts each at its recorded position), unless a refresh has already put
     * the item back, or the server has since stopped showing it to this user (it must not reappear in their list).
     */
    suspend fun restore(s: Session, t: Touched) {
        for ((itemId, before) in t.rows) {
            if (before == null) store.deleteUserData(s, itemId) else store.restoreUserData(before.copy(pendingSyncAt = null))
        }
        for ((itemId, moved) in t.badges) {
            val count = store.userData(s, itemId)?.unplayedItemCount ?: continue
            store.setUnplayedCount(s, itemId, (count - moved).coerceAtLeast(0), clock())
        }
        clearPending(s, t.badges.keys)
        for (e in t.removed.asReversed()) {
            if (store.visibleItem(s, e.itemId) == null) continue
            val now = store.entryIds(s, e.listKey)
            if (e.itemId !in now) store.restoreEntry(s, e.listKey, e.position.coerceAtMost(now.size), e.itemId)
        }
    }

    private companion object {
        const val MAX_CHILDREN = 10_000
    }
}
