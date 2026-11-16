package app.shoalwatch.data.db

import app.shoalwatch.S1
import app.shoalwatch.S2
import app.shoalwatch.U1
import app.shoalwatch.data.db.library.EvictionPolicy
import app.shoalwatch.data.library.ItemKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Eviction: what goes, and what never does. */
class LibraryMaintenanceTest : LibraryTestBase() {
    private val now: Instant = t0.plus(Duration.ofDays(100))
    private val longAgo: Instant = t0
    private val recently: Instant = now.minus(Duration.ofDays(1))

    private fun itemIds(serverId: String = S1) =
        db.openHelper.readableDatabase.query("SELECT itemId FROM items WHERE serverId = '$serverId'").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

    private fun ids(vararg labels: String) = labels.map(::id).toSet()

    private fun pin(label: String) = sql("UPDATE items SET pinned = 1 WHERE itemId = ?", id(label))

    @Test fun listsNotRefreshedSinceTheCutoffAreDeletedWithTheirState() = runBlocking {
        library.writePage(s1u1, "stale", 0, listOf(row(item("a")), row(item("b"))), 2, longAgo, requestedAtSeq = 0)
        library.writePage(s1u1, "fresh", 0, listOf(row(item("c"))), 1, recently, requestedAtSeq = 0)
        val counts = library.evict(now)
        assertEquals(2, counts.listEntries)
        assertEquals(1, counts.listStates)
        assertEquals(0, db.listDao().countEntries(S1, U1, "stale"))
        assertEquals(null, db.listDao().getState(S1, U1, "stale"))
        assertEquals(1, db.listDao().countEntries(S1, U1, "fresh"))
        assertEquals(1, db.listDao().getState(S1, U1, "fresh")!!.total)
    }

    @Test fun oldUnreferencedItemsGoAndRecentOrReferencedOrPinnedOnesStay() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("old")), row(item("old-pinned")), row(item("old-listed"))), longAgo, requestedAtSeq = 0)
        library.writeItems(s1u1, listOf(row(item("recent"))), recently, requestedAtSeq = 0)
        library.writePage(s1u1, "resume", 0, listOf(row(item("old-listed"))), 1, recently, requestedAtSeq = 0)
        pin("old-pinned")
        val counts = library.evict(now)
        assertEquals(ids("old-pinned", "old-listed", "recent"), itemIds())
        assertEquals(1, counts.items)
    }

    @Test fun anItemListedByAnyUserIsKept() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("a"))), longAgo, requestedAtSeq = 0)
        library.writePage(s1u2, "resume", 0, listOf(row(item("a"))), 1, recently, requestedAtSeq = 0)
        library.evict(now)
        assertEquals(ids("a"), itemIds())
    }

    @Test fun theSeasonAndSeriesOfAListedEpisodeAreKept() = runBlocking {
        val series = item("show", ItemKind.SERIES)
        val season = item("season", ItemKind.SEASON, seriesId = "show", parentId = "show")
        val ep = item("ep", ItemKind.EPISODE, seriesId = "show", seasonId = "season", parentId = "season")
        library.writeItems(s1u1, listOf(row(series), row(season), row(ep), row(item("stray"))), longAgo, requestedAtSeq = 0)
        library.writePage(s1u1, "resume", 0, listOf(row(ep)), 1, recently, requestedAtSeq = 0)
        library.evict(now)
        assertEquals(ids("show", "season", "ep"), itemIds())
    }

    @Test fun theSeasonAndSeriesOfAPinnedEpisodeAreKept() = runBlocking {
        val series = item("show", ItemKind.SERIES)
        val season = item("season", ItemKind.SEASON, seriesId = "show", parentId = "show")
        val ep = item("ep", ItemKind.EPISODE, seriesId = "show", seasonId = "season", parentId = "season")
        library.writeItems(s1u1, listOf(row(series), row(season), row(ep), row(item("stray"))), longAgo, requestedAtSeq = 0)
        pin("ep")
        library.evict(now)
        assertEquals(ids("show", "season", "ep"), itemIds())
    }

    @Test fun aSeriesWhoseEpisodesAreNeitherListedNorPinnedIsEvictedToo() = runBlocking {
        val series = item("show", ItemKind.SERIES)
        val ep = item("ep", ItemKind.EPISODE, seriesId = "show", seasonId = "season", parentId = "season")
        library.writeItems(s1u1, listOf(row(series), row(ep)), longAgo, requestedAtSeq = 0)
        library.evict(now)
        assertEquals(emptySet<String>(), itemIds())
    }

    @Test fun itemsOfAStaleListBecomeEvictableInTheSamePass() = runBlocking {
        library.writePage(s1u1, "stale", 0, listOf(row(item("a")), row(item("b"))), 2, longAgo, requestedAtSeq = 0)
        val counts = library.evict(now)
        assertEquals(emptySet<String>(), itemIds())
        assertEquals(2, counts.items)
    }

    @Test fun watchedStateSurvivesEvictionOfItsItem() = runBlocking {
        library.writeItems(
            s1u1,
            listOf(
                row(item("seen"), watched(played = true)), row(item("fav"), watched(favourite = true)),
                row(item("resume"), watched(positionMs = 1_000)), row(item("nothing"), watched()), row(item("kept-item"), watched()),
            ),
            longAgo, requestedAtSeq = 0,
        )
        library.writeItems(s1u1, listOf(row(item("kept-item"))), recently, requestedAtSeq = 0)
        val counts = library.evict(now)
        val survivors = buildSet { listOf("seen", "fav", "resume", "nothing", "kept-item").forEach { if (db.itemDao().getUserData(S1, U1, id(it)) != null) add(it) } }
        assertEquals(setOf("seen", "fav", "resume", "kept-item"), survivors)
        assertEquals(1, counts.userData)
        assertEquals(ids("kept-item"), itemIds())
    }

    @Test fun theCapRemovesTheOldestUnprotectedItemsFirst() = runBlocking {
        val policy = EvictionPolicy(listMaxAge = Duration.ofDays(365), itemMaxAge = Duration.ofDays(365), maxUnpinnedItems = 6)
        (1..10).forEach { library.writeItems(s1u1, listOf(row(item("f$it"))), now.minusSeconds(1_000L - it), requestedAtSeq = 0) } // f1 oldest
        library.writeItems(s1u1, listOf(row(item("listed1")), row(item("listed2"))), now.minusSeconds(5_000), requestedAtSeq = 0) // oldest of all, but listed
        library.writePage(s1u1, "resume", 0, listOf(row(item("listed1")), row(item("listed2"))), 2, now, requestedAtSeq = 0)
        library.writeItems(s1u1, listOf(row(item("pinned"))), now.minusSeconds(9_000), requestedAtSeq = 0)
        pin("pinned")
        val counts = library.evict(now, policy)
        // 12 unpinned (10 + 2 listed): 6 must go, and only the oldest of the 10 unprotected ones
        assertEquals(6, counts.items)
        assertEquals(ids("f7", "f8", "f9", "f10", "listed1", "listed2", "pinned"), itemIds())
    }

    @Test fun theCapNeverDeletesProtectedRowsEvenIfItIsStillExceeded() = runBlocking {
        val policy = EvictionPolicy(itemMaxAge = Duration.ofDays(365), listMaxAge = Duration.ofDays(365), maxUnpinnedItems = 1)
        library.writePage(s1u1, "resume", 0, listOf(row(item("a")), row(item("b")), row(item("c"))), 3, now, requestedAtSeq = 0)
        assertEquals(0, library.evict(now, policy).items)
        assertEquals(3, itemIds().size)
    }

    @Test fun pinnedRowsDoNotCountTowardsTheCap() = runBlocking {
        val policy = EvictionPolicy(itemMaxAge = Duration.ofDays(365), listMaxAge = Duration.ofDays(365), maxUnpinnedItems = 2)
        library.writeItems(s1u1, (1..5).map { row(item("p$it")) } + row(item("x")) + row(item("y")), now, requestedAtSeq = 0)
        (1..5).forEach { pin("p$it") }
        assertEquals(0, library.evict(now, policy).items)
        assertEquals(7, itemIds().size)
    }

    @Test fun eachServerIsEvictedOnItsOwn() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("a"))), longAgo, requestedAtSeq = 0)
        library.writeItems(s2u1, listOf(row(item("a"))), longAgo, requestedAtSeq = 0)
        val dao = db.libraryMaintenanceDao()
        val counts = dao.evict(S1, now.minus(Duration.ofDays(30)).toEpochMilli(), now.minus(Duration.ofDays(30)).toEpochMilli(), 100)
        assertEquals(1, counts.items)
        assertEquals(emptySet<String>(), itemIds(S1))
        assertEquals(ids("a"), itemIds(S2))
        assertTrue(library.evict(now).items == 1)
        assertEquals(emptySet<String>(), itemIds(S2))
    }

    @Test fun aPinnedSeriesKeepsItsSeasonsListAndAnUnpinnedOneLosesIt() = runBlocking {
        val seasons = { series: String -> listOf(row(item("$series-s1", ItemKind.SEASON, seriesId = series, indexNumber = 1))) }
        library.writePage(s1u1, "seasons:${id("kept")}", 0, seasons("kept"), 1, longAgo, requestedAtSeq = 0)
        library.writePage(s1u1, "seasons:${id("gone")}", 0, seasons("gone"), 1, longAgo, requestedAtSeq = 0)
        library.writePage(s1u1, "children:${id("kept")}", 0, seasons("kept"), 1, longAgo, requestedAtSeq = 0)
        library.writeItems(s1u1, listOf(row(item("kept", ItemKind.SERIES)), row(item("gone", ItemKind.SERIES))), longAgo, requestedAtSeq = 0)
        library.writeItems(s2u1, listOf(row(item("kept", ItemKind.SERIES))), longAgo, requestedAtSeq = 0)
        library.writePage(s2u1, "seasons:${id("kept")}", 0, seasons("kept"), 1, longAgo, requestedAtSeq = 0)
        pin("kept")
        sql("UPDATE items SET pinned = 0 WHERE serverId = ?", S2) // only S1's row of the series is pinned
        library.evict(now, EvictionPolicy(Duration.ZERO, Duration.ZERO, 0))
        assertEquals(1, db.listDao().countEntries(S1, U1, "seasons:${id("kept")}"))
        assertEquals(1, count("list_state", "serverId = '$S1' AND listKey = 'seasons:${id("kept")}'"))
        assertEquals("only the seasons list is kept", 0, db.listDao().countEntries(S1, U1, "children:${id("kept")}"))
        assertEquals(0, db.listDao().countEntries(S1, U1, "seasons:${id("gone")}"))
        assertEquals(0, count("list_state", "serverId = '$S1' AND listKey = 'seasons:${id("gone")}'"))
        assertEquals("a pin on one server keeps nothing on another", 0, db.listDao().countEntries(S2, U1, "seasons:${id("kept")}"))
    }

    @Test fun evictingAnEmptyCacheDoesNothing() = runBlocking {
        val counts = library.evict(now)
        assertEquals(0, counts.listEntries + counts.listStates + counts.items + counts.userData)
    }
}
