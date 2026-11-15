package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
        library.writeItems(s1u1, listOf(row(item("a", overview = null))), t0, requestedAtSeq = 0) // as a list leaves it
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

    @Test fun aDetailRefreshIsAuthoritativeSoAnOverviewTheServerNoLongerHasIsCleared() = runBlocking {
        library.writeItems(s1u1, listOf(full("a")), t0, requestedAtSeq = 0)
        gateway.itemFor = { row(item("a", overview = null)) }
        repo.refresh(s1u1, RefreshTarget.Item(id("a")))
        assertNull(detail().first5s { it.item != null }.item!!.item.overview)
    }

    @Test fun aRefreshNeverUndoesAWatchedMarkWaitingToBeSent() = runBlocking {
        repo.ensureSwept() // the start-up sweep has run, so this marker belongs to a live action
        library.writeItems(s1u1, listOf(row(item("a"), watched())), t0, requestedAtSeq = 0)
        db.itemDao().setPlayed(s1u1.serverId, s1u1.userId, id("a"), true, 0, t0.toEpochMilli(), pending = true)
        gateway.itemFor = { row(item("a"), watched(played = false, positionMs = 900)) }
        repo.refresh(s1u1, RefreshTarget.Item(id("a")))
        val d = detail().first5s { it.item != null }.item!!
        assertTrue(d.userData!!.played)
        assertEquals(0L, d.userData!!.positionMs)
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

    @Test fun anotherUserIsNeverShownARowOnlyTheFirstUserWasShown() {
        runBlocking { library.writeItems(s1u1, listOf(row(item("a", overview = "x"), watched(played = true))), t0, requestedAtSeq = 0) }
        gateway.failAll = AppError.Kind.OFFLINE
        val bob = repo.item(s1u2, id("a")).await { it.freshness is Freshness.Stale }
        assertNull(bob.item) // the row is on the phone, but the server never showed it to Bob
        assertEquals(Freshness.Stale(AppError.Kind.OFFLINE), bob.freshness)
    }

    @Test fun onceTheServerReturnsTheItemToTheOtherUserTheySeeItWithTheirOwnWatchedState() {
        runBlocking { library.writeItems(s1u1, listOf(row(item("a", overview = "x"), watched(played = true))), t0, requestedAtSeq = 0) }
        gateway.itemFor = { row(item("a", overview = "x")) }
        val bob = repo.item(s1u2, id("a")).await { it.item != null }
        assertEquals("x", bob.item!!.item.overview)
        assertNull(bob.item!!.userData) // none of Ann's watched state
    }

    @Test fun offlineSearchAndSeriesNeverRevealWhatTheOtherUserCached() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("m", name = "Secret Film")), row(episode("e1", 1, 1))), t0, requestedAtSeq = 0)
        library.writePage(s1u1, ListKey.seasons(id("show")), 0, listOf(row(item("season1", ItemKind.SEASON, "Season 1", seriesId = "show", indexNumber = 1))), 1, t0, requestedAtSeq = 0)
        gateway.failAll = AppError.Kind.OFFLINE
        assertEquals(emptyList<String>(), names(repo.search(s1u2, "secret").items))
        assertEquals(1, repo.search(s1u1, "secret").items.size)
        val bob = repo.series(s1u2, id("show")).first5s { it.freshness is Freshness.Stale }
        assertEquals(emptyList<ItemWithUserData>(), bob.seasons)
        assertEquals(emptyList<ItemWithUserData>(), bob.episodes)
        assertNull(bob.series)
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
        library.writeItems(s1u1, listOf(row(item("a", name = "The Matrix Reloaded")), row(item("b", name = "Other")), row(item("c", ItemKind.EPISODE, "matrix ep"))), t0, requestedAtSeq = 0)
        for (kind in listOf(AppError.Kind.OFFLINE, AppError.Kind.NETWORK, AppError.Kind.TIMEOUT)) {
            gateway.failAll = kind
            val result = repo.search(s1u1, "MATRIX")
            assertTrue(kind.name, result.offline)
            assertEquals(setOf("The Matrix Reloaded", "matrix ep"), result.items.map { it.item.name }.toSet())
        }
    }

    @Test fun offlineSearchShowsThisUsersWatchedState() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("a", name = "Matrix"), watched(played = true))), t0, requestedAtSeq = 0)
        library.writeItems(s1u2, listOf(row(item("a", name = "Matrix"))), t0, requestedAtSeq = 0) // shown to Bob too, with nothing of his own
        gateway.failAll = AppError.Kind.OFFLINE
        assertTrue(repo.search(s1u1, "matrix").items.single().userData!!.played)
        assertNull(repo.search(s1u2, "matrix").items.single().userData)
    }

    @Test fun otherFailuresAreNotAnExcuseToSearchTheSavedCopy() = runBlocking {
        library.writeItems(s1u1, listOf(row(item("a", name = "Matrix"))), t0, requestedAtSeq = 0)
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
            library.writePage(s1u1, "old-list", 0, films("listed"), 1, t0, requestedAtSeq = 0) // 40 days old
            library.writeItems(s1u1, films("loose", "pinned"), t0, requestedAtSeq = 0)
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
        library.writePage(s1u1, "another-old", 0, films("x"), 1, t0, requestedAtSeq = 0)
        now += day - 1_000
        repo.home(s1u1).first5s()
        settle() // every background job has finished, so "nothing ran" is a fact, not a guess
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
        settle() // every background job has finished, so "nothing ran" is a fact, not a guess
        assertEquals(emptyList<Long>(), log.records)
        assertEquals(1, count("list_entries", "listKey = 'old-list'"))
        assertFalse(idsLeft().isEmpty())
    }
}
