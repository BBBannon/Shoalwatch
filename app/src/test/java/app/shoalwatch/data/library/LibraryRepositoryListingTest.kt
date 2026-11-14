package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/** A library or folder listing: paged by what the server consumed, replaced whole on refresh. */
class LibraryRepositoryListingTest : LibraryRepositoryTestBase() {
    private val parent = id("films")
    private val shape = ListShape.forLibrary(LibraryKind.MOVIES)
    private val key = ListKey.children(parent, shape, nameAsc, noFilter)

    private fun label(n: Int) = "m%05d".format(n)

    /** A server list of [count] films; `bad` ones are consumed but come back unusable (the gateway skips them). */
    private fun serve(count: Int, bad: (Int) -> Boolean = { false }, total: Int? = count) {
        gateway.childPage = { _, start, limit ->
            val raw = (start until minOf(start + limit, count)).toList()
            val end = raw.isEmpty() || (total != null && start + raw.size >= total) || (total == null && raw.size < limit)
            Page(raw.filterNot(bad).map { row(item(label(it))) }, start, total, start + raw.size, end)
        }
    }

    private fun listing() = repo.children(s1u1, parent, shape, nameAsc, noFilter)

    private fun loaded(minimum: Int = 1) = listing().await { it.freshness == Freshness.Fresh && it.items.size >= minimum }

    private suspend fun more() = repo.loadMore(s1u1, parent, shape, nameAsc, noFilter)

    @Test fun theFirstPageComesFromTheServerForTheLibrarysKinds() {
        serve(250)
        val first = loaded(100)
        assertEquals(100, first.items.size)
        assertEquals(250, first.total)
        assertTrue(first.canLoadMore)
        assertEquals(listOf("children:0:100:movie_r"), gateway.calls.filter { it.startsWith("children") })
    }

    @Test fun loadMoreAppendsInOrderAndStopsAtTheTotal() = runBlocking {
        serve(250)
        loaded(100)
        more()
        assertEquals(200, listing().first5s { it.items.size == 200 }.items.size)
        more()
        val all = listing().first5s { it.items.size == 250 && it.freshness == Freshness.Fresh }
        assertEquals((0 until 250).map { "Film ${label(it)}" }, names(all.items))
        assertFalse(all.canLoadMore)
        more() // all loaded: nothing happens
        assertEquals(3, gateway.count("children"))
        assertEquals(listOf("children:0:100:movie_r", "children:100:100:movie_r", "children:200:100:movie_r"), gateway.calls.filter { it.startsWith("children") })
    }

    @Test fun loadMoreContinuesFromTheServersIndexNotFromHowManyRowsAreStored() = runBlocking {
        serve(300, bad = { it % 5 == 0 }) // 20 of every 100 are unusable
        val first = loaded(80)
        assertEquals(80, first.items.size)
        more()
        val second = listing().first5s { it.items.size == 160 }
        assertEquals(160, second.items.map { it.item.id }.toSet().size) // no repeats
        assertEquals("children:100:100:movie_r", gateway.calls.last { it.startsWith("children") }) // not 80
        more()
        assertEquals(240, listing().first5s { it.items.size == 240 && it.freshness == Freshness.Fresh }.items.size)
    }

    @Test fun anEmptyKeptPageIsNotTheEnd() = runBlocking {
        serve(300, bad = { it < 100 })
        val first = listing().first5s { it.freshness == Freshness.Fresh }
        assertEquals(emptyList<ItemWithUserData>(), first.items)
        assertTrue(first.canLoadMore) // the server has not said it is finished
        more()
        val next = listing().first5s { it.items.isNotEmpty() }
        assertEquals(100, next.items.size)
        assertEquals("children:100:100:movie_r", gateway.calls.last { it.startsWith("children") })
    }

    @Test fun anUnknownTotalEndsOnAShortPageNotOnAnEmptyOne() = runBlocking {
        serve(150, total = null)
        loaded(100)
        assertNull(listing().first5s().total)
        more()
        val all = listing().first5s { it.items.size == 150 && it.freshness == Freshness.Fresh }
        assertFalse(all.canLoadMore) // 50 came back where 100 were asked for
    }

    @Test fun pagingStopsAtTwoHundredPages() = runBlocking {
        gateway.childPage = { _, start, limit -> Page((start until start + limit).map { row(item("p$it")) }, start, null, start + limit, false) }
        loaded(100)
        var guard = 0
        while (db.listDao().getState(s1u1.serverId, s1u1.userId, key)!!.pagesLoaded < 300 && guard++ < 400) more()
        assertEquals(200, gateway.count("children"))
        val last = listing().first5s { it.freshness == Freshness.Fresh && it.items.size == 20_000 }
        assertFalse(last.canLoadMore)
        more()
        assertEquals(200, gateway.count("children")) // the ceiling holds
    }

    @Test fun loadMoreDoesNothingWhileAnotherIsRunning() = runBlocking {
        serve(500)
        loaded(100)
        gateway.gate = CompletableDeferred()
        val running = async { more() }
        until { gateway.count("children") == 2 }
        withTimeout(2_000) { more() } // returns at once, asks for nothing
        gateway.gate!!.complete(Unit)
        running.await()
        assertEquals(2, gateway.count("children"))
        assertEquals(200, listing().first5s { it.items.size == 200 }.items.size)
    }

    @Test fun loadMoreDoesNothingWhileTheListIsRefreshing() = runBlocking {
        serve(500)
        loaded(100)
        advance(Duration.ofMinutes(20))
        gateway.gate = CompletableDeferred()
        val collector = launch { listing().collect { } }
        until { gateway.count("children") == 2 }
        more()
        assertEquals(2, gateway.count("children"))
        gateway.gate!!.complete(Unit)
        collector.cancelAndJoin()
    }

    @Test fun aRefreshReplacesTheListWithPageZeroAndResetsPaging() = runBlocking {
        serve(500)
        loaded(100)
        more()
        listing().first5s { it.items.size == 200 }
        advance(Duration.ofMinutes(20))
        val after = listing().first5s { it.freshness == Freshness.Fresh && it.items.size == 100 }
        assertEquals(100, after.items.size) // the second page is dropped: the screen asks for it again by scrolling
        assertTrue(after.canLoadMore)
        more()
        assertEquals("children:100:100:movie_r", gateway.calls.last { it.startsWith("children") })
        assertEquals(200, listing().first5s { it.items.size == 200 }.items.size)
    }

    @Test fun aFailedLoadMoreChangesNothingButTheFreshness() = runBlocking {
        serve(300)
        val before = loaded(100)
        gateway.failAll = AppError.Kind.NETWORK
        more()
        val after = listing().first5s { it.freshness is Freshness.Stale }
        assertEquals(Freshness.Stale(AppError.Kind.NETWORK), after.freshness)
        assertEquals(before.items, after.items)
        assertTrue(after.canLoadMore)
        gateway.failAll = null
        more()
        val ok = listing().first5s { it.items.size == 200 && it.freshness == Freshness.Fresh }
        assertEquals((0 until 200).map { "Film ${label(it)}" }, names(ok.items))
    }

    @Test fun anItemRemovedOnTheServerDisappearsAfterARefresh() {
        val live = mutableListOf("a", "b", "c")
        gateway.childPage = { _, start, _ -> Page(live.map { row(item(it)) }, start, live.size, start + live.size, true) }
        assertEquals(listOf("Film a", "Film b", "Film c"), names(loaded(3).items))
        advance(Duration.ofMinutes(20))
        live.remove("b")
        val after = listing().await { it.freshness == Freshness.Fresh && it.items.size == 2 }
        assertEquals(listOf("Film a", "Film c"), names(after.items))
    }

    @Test fun anIdAlreadyInTheListKeepsItsFirstPosition() = runBlocking {
        val server = listOf((0 until 100).map { label(it) }, listOf(label(5)) + (100 until 199).map { label(it) })
        gateway.childPage = { _, start, _ ->
            val page = server[start / 100]
            Page(page.map { row(item(it)) }, start, null, start + 100, false)
        }
        loaded(100)
        more()
        val all = listing().first5s { it.items.size == 199 }
        assertEquals(199, all.items.map { it.item.id }.toSet().size)
        assertEquals(label(5), all.items[5].item.name.removePrefix("Film "))
    }

    @Test fun anExplicitRefreshJoinsTheOneAlreadyRunning() = runBlocking {
        serve(10)
        gateway.gate = CompletableDeferred()
        val collector = launch { listing().collect { } }
        until { gateway.count("children") == 1 }
        val pulling = async { repo.refresh(s1u1, RefreshTarget.Children(parent, shape, nameAsc, noFilter)) }
        gateway.gate!!.complete(Unit)
        pulling.await()
        collector.cancelAndJoin()
        assertEquals(1, gateway.count("children"))
        assertEquals(10, db.listDao().countEntries(s1u1.serverId, s1u1.userId, key))
    }

    @Test fun aRefreshAskedWhileLoadMoreRunsWaitsForItThenReplacesTheList() = runBlocking {
        serve(500)
        loaded(100)
        gateway.gate = CompletableDeferred()
        val loading = async { more() }
        until { gateway.count("children") == 2 }
        val refreshing = async { repo.refresh(s1u1, RefreshTarget.Children(parent, shape, nameAsc, noFilter)) }
        delay(200)
        assertEquals(2, gateway.count("children")) // not started: the two must never overlap
        gateway.gate!!.complete(Unit)
        loading.await()
        refreshing.await()
        assertEquals(listOf("children:0:100:movie_r", "children:100:100:movie_r", "children:0:100:movie_r"), gateway.calls.filter { it.startsWith("children") })
        assertEquals(100, listing().first5s { it.freshness == Freshness.Fresh && it.items.size == 100 }.items.size)
    }

    @Test fun aRefreshThatHasBegunIsSavedEvenIfItsCollectorLeaves() = runBlocking {
        serve(10)
        gateway.gate = CompletableDeferred()
        val collector = launch { listing().collect { } }
        until { gateway.count("children") == 1 }
        collector.cancelAndJoin()
        gateway.gate!!.complete(Unit)
        until { count("list_entries", "listKey = '$key'") == 10 }
    }

    @Test fun eachKindOfLibraryAsksForItsOwnKinds() {
        assertEquals(ListShape(setOf(ItemKind.MOVIE), true), ListShape.forLibrary(LibraryKind.MOVIES))
        assertEquals(ListShape(setOf(ItemKind.SERIES), true), ListShape.forLibrary(LibraryKind.TV))
        assertEquals(ListShape(setOf(ItemKind.BOX_SET), true), ListShape.forLibrary(LibraryKind.BOX_SETS))
        assertEquals(ListShape(emptySet(), false), ListShape.forLibrary(LibraryKind.MIXED))
        assertEquals(ListShape.DIRECT, ListShape.forLibrary(LibraryKind.MIXED))
    }

    @Test fun theKindsAreWhatTheGatewayIsAsked() {
        val tv = ListShape.forLibrary(LibraryKind.TV)
        repo.children(s1u1, id("tv"), tv, nameAsc, noFilter).await { it.freshness == Freshness.Fresh }
        assertEquals("children:0:100:series_r", gateway.calls.single { it.startsWith("children") })
    }
}
