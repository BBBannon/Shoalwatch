package app.shoalwatch.data.library

import app.cash.turbine.test
import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U3
import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration

/** Cache first, refresh behind: what the home screen shows and when it asks the server. */
class LibraryRepositoryHomeTest : LibraryRepositoryTestBase() {
    private fun seedResume(vararg labels: String) = runBlocking {
        library.writePage(s1u1, ListKey.RESUME, 0, films(*labels), labels.size, t0, requestedAtSeq = 0)
    }

    @Test fun savedRowsAreEmittedBeforeTheNetworkAnswersThenTheRefreshLands() = runBlocking {
        seedResume("a", "b")
        advance(Duration.ofMinutes(5)) // older than the 2-minute window
        gateway.gate = CompletableDeferred()
        gateway.resumeFor = { films("c") }
        repo.home(s1u1).test {
            val first = awaitItem()
            assertEquals(listOf("Film a", "Film b"), names(first.resume.items)) // from Room, the server has not answered
            assertEquals(Freshness.Refreshing, first.resume.freshness)
            gateway.gate!!.complete(Unit)
            val last = awaitUntil { it.resume.freshness == Freshness.Fresh && names(it.resume.items) == listOf("Film c") }
            assertEquals(Freshness.Fresh, last.resume.freshness)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun nothingIsRequestedWhileASavedListIsWithinItsWindow() {
        seedResume("a")
        repo.home(s1u1).await { it.resume.freshness == Freshness.Fresh && it.resume.items.isNotEmpty() }
        assertEquals(0, gateway.count("resume"))
    }

    @Test fun aListOlderThanItsWindowIsRefreshedOnTheNextCollect() {
        seedResume("a")
        advance(Duration.ofMinutes(1))
        repo.home(s1u1).await { it.resume.freshness == Freshness.Fresh }
        assertEquals(0, gateway.count("resume"))
        advance(Duration.ofMinutes(2))
        gateway.resumeFor = { films("z") }
        repo.home(s1u1).await { names(it.resume.items) == listOf("Film z") && it.resume.freshness == Freshness.Fresh }
        assertEquals(1, gateway.count("resume"))
    }

    // ---- round 3 (1.1.0): Home catches up when it comes back, and after a scan

    private fun homeLoaded() {
        gateway.viewsFor = { listOf(view("m")) }
        repo.home(s1u1).await { h -> h.latest.size == 1 && h.resume.freshness == Freshness.Fresh && h.nextUp.freshness == Freshness.Fresh && h.latest.all { it.second.freshness == Freshness.Fresh } }
        runBlocking { settle() }
        gateway.calls.clear()
    }

    private fun homeCalls() = gateway.calls.filter { it.startsWith("resume") || it.startsWith("nextUp") || it.startsWith("latest") || it.startsWith("views") }

    @Test fun comingBackAfterTheWindowAsksForEveryHomeListAgain() = runBlocking {
        homeLoaded()
        advance(Duration.ofMinutes(3))
        repo.refreshHomeIfDue(s1u1)
        until { homeCalls().size == 4 }
        settle()
        assertEquals(listOf("latest", "nextUp", "resume", "views"), homeCalls().map { it.substringBefore(':') }.sorted())
    }

    @Test fun comingBackWithinTheWindowAsksNothingHoweverOften() = runBlocking {
        homeLoaded()
        advance(Duration.ofMinutes(1))
        repeat(10) { repo.refreshHomeIfDue(s1u1) } // back and forth
        settle()
        assertEquals(emptyList<String>(), homeCalls())
    }

    @Test fun aListThatJustFailedIsNotAskedAgainOnEveryReturn() = runBlocking {
        homeLoaded()
        advance(Duration.ofMinutes(3))
        gateway.failAll = AppError.Kind.OFFLINE
        repo.refreshHomeIfDue(s1u1); settle()
        val after = homeCalls().size
        repeat(5) { repo.refreshHomeIfDue(s1u1) }
        settle()
        assertEquals("the 30 s cooldown holds", after, homeCalls().size)
    }

    @Test fun aFinishedScanMakesHomeDueEvenWithinItsWindowAndOnlyHome() = runBlocking {
        homeLoaded()
        library.writePage(s1u1, ListKey.latest(id("other")), 0, films("x"), 1, t0, requestedAtSeq = 0) // not one of Home's lists
        suspend fun other() = db.listDao().getState(s1u1.serverId, s1u1.userId, ListKey.latest(id("other")))!!.refreshedAt
        val before = other()
        advance(Duration.ofSeconds(30))
        repo.markHomeDue(s1u1)
        assertEquals("marking asks nothing by itself", emptyList<String>(), homeCalls())
        assertEquals("a list that is not Home's stays as it was", before, other())
        repo.refreshHomeIfDue(s1u1)
        until { homeCalls().size == 4 }
        settle()
    }

    /** Round 5 (re-review I1): "due" is just past the window, never so old that eviction deletes Home's saved rows. */
    @Test fun markingHomeDueNeverLetsEvictionDeleteHomesRows() = runBlocking {
        seedResume("a")
        homeLoaded()
        gateway.failAll = AppError.Kind.OFFLINE // the refresh after the scan fails, or Home is not reopened
        repo.markHomeDue(s1u1)
        library.evict(java.time.Instant.ofEpochMilli(now))
        assertTrue("Home's list state is still there", db.listDao().getState(s1u1.serverId, s1u1.userId, ListKey.RESUME) != null)
        val state = db.listDao().getState(s1u1.serverId, s1u1.userId, ListKey.RESUME)!!
        assertTrue("and it counts as due", now - state.refreshedAt >= Duration.ofMinutes(2).toMillis())
        assertTrue("but only just", now - state.refreshedAt < Duration.ofMinutes(3).toMillis())
    }

    @Test fun limitsAreTwentyFourTwentyFourAndSixteenPerLibrary() {
        gateway.viewsFor = { listOf(view("m"), view("t", LibraryKind.TV)) }
        repo.home(s1u1).await { it.latest.size == 2 && it.latest.all { (_, row) -> row.freshness == Freshness.Fresh } && it.resume.freshness == Freshness.Fresh && it.nextUp.freshness == Freshness.Fresh }
        assertEquals(1, gateway.calls.count { it == "resume:24" })
        assertEquals(1, gateway.calls.count { it == "nextUp:24" })
        assertEquals(2, gateway.calls.count { it == "latest:16" })
    }

    @Test fun neverLoadedAndOfflineIsEmptyAndStale() {
        gateway.failAll = AppError.Kind.OFFLINE
        val home = repo.home(s1u1).await { it.resume.freshness is Freshness.Stale && it.nextUp.freshness is Freshness.Stale } // both rows, they finish independently
        assertEquals(emptyList<ItemWithUserData>(), home.resume.items)
        assertEquals(Freshness.Stale(AppError.Kind.OFFLINE), home.resume.freshness)
        assertEquals(Freshness.Stale(AppError.Kind.OFFLINE), home.nextUp.freshness)
    }

    @Test fun everyFailureKindShowsAsStaleWithItsKindAndTheSavedRowsStay() {
        listOf(
            AppError.Kind.OFFLINE, AppError.Kind.NETWORK, AppError.Kind.TIMEOUT, AppError.Kind.SERVER_ERROR,
            AppError.Kind.SERVER_NOT_FOUND, AppError.Kind.TLS_UNTRUSTED, AppError.Kind.TLS_PIN_CHANGED,
            AppError.Kind.UNAUTHORISED, AppError.Kind.NOT_SIGNED_IN,
        ).forEach { kind ->
            seedResume("a", "b")
            advance(Duration.ofMinutes(10))
            gateway.failAll = kind
            val home = repo.home(s1u1).await { it.resume.freshness is Freshness.Stale }
            assertEquals(kind.name, Freshness.Stale(kind), home.resume.freshness)
            assertEquals(listOf("Film a", "Film b"), names(home.resume.items)) // nothing deleted
        }
    }

    @Test fun freshnessGoesRefreshingThenFresh() = runBlocking {
        gateway.gate = CompletableDeferred()
        gateway.resumeFor = { films("a") }
        repo.home(s1u1).test {
            assertEquals(Freshness.Refreshing, awaitItem().resume.freshness)
            gateway.gate!!.complete(Unit)
            val seen = awaitUntil { it.resume.freshness != Freshness.Refreshing }
            assertEquals(Freshness.Fresh, seen.resume.freshness)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun oneFailingRowDoesNotBlankTheOthers() {
        gateway.viewsFor = { listOf(view("m")) }
        gateway.failOps["resume"] = AppError.Kind.SERVER_ERROR
        gateway.nextUpFor = { films("n") }
        gateway.latestFor = { _, _ -> films("l") }
        val home = repo.home(s1u1).await {
            it.nextUp.freshness == Freshness.Fresh && it.latest.singleOrNull()?.second?.freshness == Freshness.Fresh && it.resume.freshness is Freshness.Stale
        }
        assertEquals(Freshness.Stale(AppError.Kind.SERVER_ERROR), home.resume.freshness)
        assertEquals(listOf("Film n"), names(home.nextUp.items))
        assertEquals(listOf("Film l"), names(home.latest.single().second.items))
    }

    @Test fun theServersLibrariesReplaceTheSavedOnesIncludingRemovals() {
        gateway.viewsFor = { listOf(view("a"), view("b")) }
        repo.home(s1u1).await { it.views.size == 2 && it.latest.all { (_, r) -> r.freshness == Freshness.Fresh } }
        advance(Duration.ofMinutes(3))
        gateway.viewsFor = { listOf(view("b")) }
        val home = repo.home(s1u1).await { it.views.map { v -> v.id } == listOf(id("b")) }
        assertEquals(listOf("Lib b"), home.views.map { it.name })
    }

    @Test fun concurrentCollectorsCauseOneRequestPerList() = runBlocking {
        gateway.gate = CompletableDeferred()
        gateway.resumeFor = { films("a") }
        val collectors = List(5) { async(Dispatchers.Default) { repo.home(s1u1).first5s { it.resume.items.isNotEmpty() } } }
        until { gateway.count("resume") >= 1 }
        gateway.gate!!.complete(Unit)
        collectors.forEach { it.await() }
        assertEquals(1, gateway.count("resume"))
        assertEquals(1, gateway.count("nextUp"))
        assertEquals(1, gateway.count("views"))
    }

    @Test fun aRefreshThatHasBegunIsSavedEvenIfItsCollectorLeaves() = runBlocking {
        gateway.gate = CompletableDeferred()
        gateway.resumeFor = { films("a", "b") }
        val collector = launch { repo.home(s1u1).collect { } }
        until { gateway.count("resume") >= 1 }
        collector.cancelAndJoin()
        gateway.gate!!.complete(Unit)
        until { count("list_state", "listKey = 'resume'") > 0 }
        assertEquals(listOf("Film a", "Film b"), db.listDao().getList(S1, U1, ListKey.RESUME, 10).map { it.item.name })
    }

    @Test fun pullToRefreshAsksAgainEvenWithinTheWindow() = runBlocking {
        seedResume("a")
        gateway.resumeFor = { films("b") }
        repo.refresh(s1u1, RefreshTarget.Home)
        assertEquals(1, gateway.count("resume"))
        assertEquals(listOf("Film b"), db.listDao().getList(S1, U1, ListKey.RESUME, 10).map { it.item.name })
    }

    @Test fun homeRowsAreRequestedFourAtATimeAtMost() = runBlocking {
        // The libraries answer at once; the rows that follow are all held at a gate set up as the libraries answer.
        gateway.viewsFor = { gateway.gate = CompletableDeferred(); (1..9).map { view("v$it") } }
        val collector = launch { repo.home(s1u1).collect { } }
        until { gateway.maxRunning >= 4 }
        delay(200) // time for any fifth request to wrongly start
        assertEquals(4, gateway.maxRunning)
        gateway.gate!!.complete(Unit)
        until { gateway.count("latest") == 9 && gateway.count("resume") == 1 && gateway.count("nextUp") == 1 }
        collector.cancelAndJoin()
        assertTrue("saw ${gateway.maxRunning} at once", gateway.maxRunning <= 4)
    }

    // ---- two users on one server

    @Test fun twoUsersOnOneServerNeverSeeEachOthersListsOrWatchedStateButShareItemRows() {
        val shared = item("shared")
        gateway.resumeFor = { s -> listOf(row(shared, if (s.userId == U1) watched(played = true) else watched(positionMs = 5_000))) }
        val ann = repo.home(s1u1).await { it.resume.freshness == Freshness.Fresh && it.resume.items.isNotEmpty() }
        val bob = repo.home(s1u2).await { it.resume.freshness == Freshness.Fresh && it.resume.items.isNotEmpty() }
        assertEquals(true, ann.resume.items.single().userData!!.played)
        assertEquals(false, bob.resume.items.single().userData!!.played)
        assertEquals(5_000L, bob.resume.items.single().userData!!.positionMs)
        assertEquals(1, count("items", "serverId = '$S1'")) // one shared item row
        // Ann's list is still hers after Bob has been in.
        assertEquals(true, repo.home(s1u1).await().resume.items.single().userData!!.played)
        // A user who has never been here sees nothing of Ann's.
        gateway.failAll = AppError.Kind.OFFLINE
        runBlocking { servers.upsertAccount(Account(S1, U3, "cat", "tok-9"), makeActive = false) }
        val cat = repo.home(Session(S1, U3)).await { it.resume.freshness is Freshness.Stale }
        assertEquals(emptyList<ItemWithUserData>(), cat.resume.items)
    }
}
