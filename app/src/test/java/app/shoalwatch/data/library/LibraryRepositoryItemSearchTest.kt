package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration

/** One item's detail, search online and offline, and the daily clear-out. */
class LibraryRepositoryItemSearchTest : LibraryRepositoryTestBase() {
    private fun detail(itemId: String = id("a")) = repo.item(s1u1, itemId)

    private fun full(label: String, overview: String = "The full story") = row(item(label, overview = overview))

    // ---- item detail

    @Test fun theSavedRowIsShownAtOnceWithoutAnOverviewThenTheRefreshBringsOne() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("a", overview = null))), t0) // as a list leaves it
        advance(Duration.ofMinutes(10))
        gateway.gate = CompletableDeferred()
        gateway.itemFor = { full("a") }
        val first = detail().first5s()
        assertNull(first.item!!.item.overview)
        assertEquals(Freshness.Refreshing, first.freshness)
        gateway.gate!!.complete(Unit)
        val done = detail().first5s { it.freshness == Freshness.Fresh && it.item?.item?.overview != null }
        assertEquals("The full story", done.item!!.item.overview)
    }

    @Test fun anItemNeverSeenAndOfflineIsNothingWithAReason() {
        gateway.failAll = AppError.Kind.OFFLINE
        val d = detail().await { it.freshness is Freshness.Stale }
        assertNull(d.item)
        assertEquals(Freshness.Stale(AppError.Kind.OFFLINE), d.freshness)
    }

    @Test fun anItemIsRefreshedAfterFiveMinutesNotBefore() {
        gateway.itemFor = { full("a") }
        detail().await { it.freshness == Freshness.Fresh && it.item != null }
        advance(Duration.ofMinutes(4))
        detail().await { it.freshness == Freshness.Fresh && it.item != null }
        assertEquals(1, gateway.count("item"))
        advance(Duration.ofMinutes(2))
        detail().await { it.freshness == Freshness.Fresh && it.item != null }
        assertEventually { gateway.count("item") == 2 }
    }

    private fun assertEventually(condition: () -> Boolean) = runBlocking { until(condition) }

    @Test fun anExplicitRefreshJoinsTheOneAlreadyRunning() = runBlocking {
        gateway.gate = CompletableDeferred()
        gateway.itemFor = { full("a") }
        val collector = launch { detail().collect { } }
        until { gateway.count("item") == 1 }
        val pulling = async { repo.refresh(s1u1, RefreshTarget.Item(id("a"))) }
        gateway.gate!!.complete(Unit)
        pulling.await()
        collector.cancelAndJoin()
        assertEquals(1, gateway.count("item"))
    }

    @Test fun anotherUserOnTheServerSeesTheSharedRowAtOnceWithTheirOwnWatchedState() {
        gateway.itemFor = { full("a") }
        runBlocking { library.writeItems(s1u1, listOf(row(item("a", overview = "x"), watched(played = true))), t0) }
        gateway.failAll = AppError.Kind.OFFLINE
        val bob = repo.item(s1u2, id("a")).await { it.item != null }
        assertEquals("x", bob.item!!.item.overview) // the shared row, from the phone
        assertNull(bob.item!!.userData) // none of Ann's watched state
    }

    // ---- search

    @Test fun aBlankTermIsNothingAndSendsNothing() = runBlocking {
        assertEquals(SearchResult(emptyList(), false), repo.search(s1u1, "   "))
        assertEquals(0, gateway.calls.size)
    }

    @Test fun onlineSearchReturnsTheServersResultsAndSavesThemButNotAsAList() = runBlocking {
        gateway.searchFor = { listOf(full("a"), full("b")) }
        val result = repo.search(s1u1, " matrix ")
        assertEquals(SearchResult(result.items, offline = false), result)
        assertEquals(listOf("Film a", "Film b"), names(result.items))
        assertEquals("search:matrix", gateway.calls.single())
        assertEquals(2, count("items"))
        assertEquals(0, count("list_entries"))
        assertEquals(0, count("list_state"))
        assertEquals("The full story", detail().first5s { it.item != null }.item!!.item.overview) // opens at once
    }

    @Test fun offlineSearchFindsSavedNamesIgnoringCaseAndSaysSo() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("a", name = "The Matrix Reloaded")), row(item("b", name = "Other")), row(item("c", ItemKind.EPISODE, "matrix ep"))), t0)
        for (kind in listOf(AppError.Kind.OFFLINE, AppError.Kind.NETWORK, AppError.Kind.TIMEOUT)) {
            gateway.failAll = kind
            val result = repo.search(s1u1, "MATRIX")
            assertTrue(kind.name, result.offline)
            assertEquals(setOf("The Matrix Reloaded", "matrix ep"), result.items.map { it.item.name }.toSet())
        }
    }

    @Test fun offlineSearchShowsThisUsersWatchedState() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("a", name = "Matrix"), watched(played = true))), t0)
        gateway.failAll = AppError.Kind.OFFLINE
        assertTrue(repo.search(s1u1, "matrix").items.single().userData!!.played)
        assertNull(repo.search(s1u2, "matrix").items.single().userData)
    }

    @Test fun otherFailuresAreNotAnExcuseToSearchTheSavedCopy() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("a", name = "Matrix"))), t0)
        gateway.failAll = AppError.Kind.UNAUTHORISED
        try {
            repo.search(s1u1, "matrix")
            fail("expected an AppError")
        } catch (e: AppError) {
            assertEquals(AppError.Kind.UNAUTHORISED, e.kind)
        }
    }

    // ---- the daily clear-out

    private val day = Duration.ofDays(1).toMillis()

    private fun agedWorld() {
        now = t0.toEpochMilli() + Duration.ofDays(40).toMillis()
        runBlocking {
            library.writePage(s1u1, "old-list", 0, films("listed"), 1, t0) // 40 days old
            library.writeItems(s1u1, films("loose", "pinned"), t0)
            sql("UPDATE items SET pinned = 1 WHERE itemId = ?", id("pinned"))
        }
    }

    private fun idsLeft() = db.openHelper.readableDatabase.query("SELECT itemId FROM items").use { c -> buildSet { while (c.moveToNext()) add(c.getString(0)) } }

    @Test fun theFirstUseClearsOutOldListsAndItemsButNeverAPinnedRow() = runBlocking {
        agedWorld()
        gateway.failAll = AppError.Kind.OFFLINE
        repo.home(s1u1).first5s()
        until { log.records.size == 1 }
        assertEquals(0, count("list_entries", "listKey = 'old-list'"))
        assertEquals(setOf(id("pinned")), idsLeft())
        assertEquals(now, log.records.single())
    }

    @Test fun itRunsAtMostOncePerDay() = runBlocking {
        agedWorld()
        gateway.failAll = AppError.Kind.OFFLINE
        repo.home(s1u1).first5s()
        until { log.records.size == 1 }
        library.writePage(s1u1, "another-old", 0, films("x"), 1, t0)
        now += day - 1_000
        repo.home(s1u1).first5s()
        delay(300)
        assertEquals(1, log.records.size)
        assertEquals(1, count("list_entries", "listKey = 'another-old'"))
        now += 2_000
        repo.home(s1u1).first5s()
        until { log.records.size == 2 }
        assertEquals(0, count("list_entries", "listKey = 'another-old'"))
    }

    @Test fun aRunRecordedBeforeARestartIsRemembered() = runBlocking {
        agedWorld()
        log.last = now - day / 2
        gateway.failAll = AppError.Kind.OFFLINE
        repo.home(s1u1).first5s()
        delay(300)
        assertEquals(emptyList<Long>(), log.records)
        assertEquals(1, count("list_entries", "listKey = 'old-list'"))
        assertFalse(idsLeft().isEmpty())
    }
}
