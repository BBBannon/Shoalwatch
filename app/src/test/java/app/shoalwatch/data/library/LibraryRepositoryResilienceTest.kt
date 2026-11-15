package app.shoalwatch.data.library

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/** Failures of every kind end up as a freshness, never a stuck spinner, a storm of requests or another user's state. */
class LibraryRepositoryResilienceTest : LibraryRepositoryTestBase() {
    private val thirtySeconds = Duration.ofSeconds(30)

    // ---- anything that is not an AppError

    @Test fun aNonAppErrorFromTheGatewayBecomesStaleUnknownNotAStuckSpinner() {
        gateway.throwOps["resume"] = IllegalStateException("boom")
        gateway.nextUpFor = { films("n") }
        val home = repo.home(s1u1).await { it.resume.freshness is Freshness.Stale && it.nextUp.freshness == Freshness.Fresh }
        assertEquals(Freshness.Stale(AppError.Kind.UNKNOWN), home.resume.freshness)
    }

    @Test fun aNonAppErrorFromTheStoreBecomesStaleUnknownToo() {
        // The database refuses a write for an account that does not exist (a foreign key), which is not an AppError.
        val ghost = Session(S1, id("ghost"))
        gateway.resumeFor = { films("a") }
        val home = repo.home(ghost).await { it.resume.freshness is Freshness.Stale }
        assertEquals(Freshness.Stale(AppError.Kind.UNKNOWN), home.resume.freshness)
    }

    // ---- state is per user

    @Test fun anotherUsersFailureIsNeverShownToThisUser() {
        gateway.resumeFor = { s -> if (s.userId == U1) throw AppError.of(AppError.Kind.OFFLINE) else films("b") }
        val ann = repo.home(s1u1).await { it.resume.freshness is Freshness.Stale }
        val bob = repo.home(s1u2).await { it.resume.freshness == Freshness.Fresh && it.resume.items.isNotEmpty() }
        assertEquals(Freshness.Stale(AppError.Kind.OFFLINE), ann.resume.freshness)
        assertEquals(listOf("Film b"), names(bob.resume.items))
        assertEquals(Freshness.Stale(AppError.Kind.OFFLINE), repo.home(s1u1).await { it.resume.freshness is Freshness.Stale }.resume.freshness)
    }

    // ---- the retry cooldown

    @Test fun aListThatKeepsFailingIsNotRequestedAgainByRepeatedCollecting() = runBlocking {
        gateway.failAll = AppError.Kind.NETWORK
        repeat(10) { repo.home(s1u1).first5s { it.resume.freshness is Freshness.Stale } } // collect, leave, collect again
        settle()
        assertEquals(1, gateway.count("resume"))
        advance(Duration.ofSeconds(29))
        repeat(5) { repo.home(s1u1).first5s { it.resume.freshness is Freshness.Stale } }
        settle()
        assertEquals(1, gateway.count("resume"))
        advance(Duration.ofSeconds(2)) // 31 s since the failure
        repo.home(s1u1).first5s { it.resume.freshness is Freshness.Stale }
        settle()
        assertEquals(2, gateway.count("resume"))
    }

    @Test fun pullToRefreshAlwaysTriesEvenInsideTheCooldown() = runBlocking {
        gateway.failAll = AppError.Kind.NETWORK
        repo.home(s1u1).first5s { it.resume.freshness is Freshness.Stale }
        settle()
        gateway.failAll = null
        gateway.resumeFor = { films("a") }
        repo.refresh(s1u1, RefreshTarget.Home)
        assertEquals(2, gateway.count("resume"))
        assertEquals(listOf("Film a"), names(repo.home(s1u1).first5s { it.resume.items.isNotEmpty() }.resume.items))
    }

    @Test fun theCooldownIsPerListAndPerUser() = runBlocking {
        gateway.failAll = AppError.Kind.NETWORK
        repo.home(s1u1).first5s { it.resume.freshness is Freshness.Stale }
        repo.home(s1u2).first5s { it.resume.freshness is Freshness.Stale }
        settle()
        assertEquals(2, gateway.count("resume")) // one per user: Ann's failure does not hold Bob back
    }

    @Test fun anAccountThatWasRemovedOrSignedOutIsForgotten() = runBlocking {
        gateway.failAll = AppError.Kind.NETWORK
        repo.home(s1u1).first5s { it.resume.freshness is Freshness.Stale }
        settle()
        repo.forget(S1, U1)
        repo.home(s1u1).first5s { it.resume.freshness is Freshness.Stale }
        settle()
        assertEquals(2, gateway.count("resume")) // no cooldown left over from before
    }

    // ---- the Refresher's own bookkeeping

    @Test fun failedEntriesAreCappedAtFiveHundredDroppingTheOldest() = runBlocking {
        val refresher = Refresher(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), Dispatchers.Unconfined, { 0L })
        for (i in 0 until 600) refresher.launch(ListId("s", "u", "k$i"), Refresher.Mode.REFRESH) { error("no") }?.join()
        val kept = refresher.failures().keys.map { it.key }
        assertEquals(500, kept.size)
        assertTrue("k599" in kept)
        assertFalse("k0" in kept)
    }

    @Test fun forgettingASessionDropsOnlyItsFailuresAndASuccessClearsAnEntry() = runBlocking {
        val refresher = Refresher(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), Dispatchers.Unconfined, { 0L })
        suspend fun fail(server: String, user: String, key: String) = refresher.launch(ListId(server, user, key), Refresher.Mode.REFRESH) { error("no") }?.join()
        fail("s", "u1", "a"); fail("s", "u1", "b"); fail("s", "u2", "a"); fail("t", "u1", "a")
        refresher.forget("s", "u1")
        assertEquals(setOf(ListId("s", "u2", "a"), ListId("t", "u1", "a")), refresher.failures().keys)
        refresher.forget("t", null)
        assertEquals(setOf(ListId("s", "u2", "a")), refresher.failures().keys)
        refresher.launch(ListId("s", "u2", "a"), Refresher.Mode.REFRESH) { }?.join()
        assertEquals(emptySet<ListId>(), refresher.failures().keys)
    }

    // ---- freshness that is not a failure

    @Test fun nothingSavedAndNothingRunningIsStaleUnknownNotAForeverSpinner() = runBlocking {
        gateway.gate = CompletableDeferred()
        val seen = CopyOnWriteArrayList<Freshness>()
        val collector = launch { repo.home(s1u1).collect { seen += it.resume.freshness } }
        until { gateway.count("resume") == 1 && seen.isNotEmpty() }
        assertEquals(Freshness.Refreshing, seen.last()) // the cold start, while the first refresh is in flight
        appScope.coroutineContext[kotlinx.coroutines.Job]!!.children.toList().forEach { it.cancel() } // the work vanishes
        until { seen.last() == Freshness.Stale(AppError.Kind.UNKNOWN) }
        gateway.gate!!.complete(Unit)
        collector.cancelAndJoin()
    }

    // ---- libraries (views) failing while rows are cached

    @Test fun aFailedLibrariesRefreshIsVisibleAndTheSavedLibrariesStay() {
        runBlocking {
            library.replaceViews(s1u1, listOf(view("a")))
            library.writePage(s1u1, ListKey.VIEWS, 0, emptyList(), 0, t0, fetchStartedAtMs = 0)
        }
        advance(Duration.ofMinutes(10))
        gateway.failOps["views"] = AppError.Kind.OFFLINE
        val home = repo.home(s1u1).await { it.viewsFreshness is Freshness.Stale }
        assertEquals(Freshness.Stale(AppError.Kind.OFFLINE), home.viewsFreshness)
        assertEquals(listOf("Lib a"), home.views.map { it.name })
    }

    @Test fun librariesThatLoadedAreFresh() {
        gateway.viewsFor = { listOf(view("a")) }
        assertEquals(Freshness.Fresh, repo.home(s1u1).await { it.viewsFreshness == Freshness.Fresh && it.views.isNotEmpty() }.viewsFreshness)
    }

    // ---- a series whose episodes are all watched

    @Test fun aFullyWatchedSeriesOpensOnItsFirstNumberedSeason() {
        val show = id("show")
        fun season(label: String, n: Int) = row(item(label, ItemKind.SEASON, "Season $label", seriesId = "show", indexNumber = n))
        runBlocking {
            library.writePage(s1u1, ListKey.seasons(show), 0, listOf(season("season1", 1), season("season2", 2)), 2, t0, fetchStartedAtMs = 0)
            library.writeItems(s1u1, listOf(row(episode("e11", 1, 1), watched(played = true)), row(episode("e21", 2, 1), watched(played = true))), t0, fetchStartedAtMs = 0)
        }
        gateway.failAll = AppError.Kind.OFFLINE
        assertEquals(id("season1"), repo.series(s1u1, show).await { it.selectedSeasonId != null }.selectedSeasonId)
    }

    // ---- an item the server says is gone

    @Test fun anItemThatIsNoLongerOnTheServerStaysVisibleAsStaleServerNotFound() {
        runBlocking { library.writeItems(s1u1, listOf(row(item("a", overview = "Plot"))), t0, fetchStartedAtMs = 0) }
        advance(Duration.ofMinutes(10))
        gateway.failOps["item"] = AppError.Kind.SERVER_NOT_FOUND
        val detail = repo.item(s1u1, id("a")).await { it.freshness is Freshness.Stale }
        assertEquals(Freshness.Stale(AppError.Kind.SERVER_NOT_FOUND), detail.freshness)
        assertEquals("Plot", detail.item!!.item.overview) // the saved row is still there
    }

    // ---- search

    @Test fun aSearchThatIsSupersededIsCancelledAndSoIsItsRequest() = runBlocking {
        gateway.gate = CompletableDeferred()
        val first = launch { repo.search(s1u1, "ma") }
        until { gateway.count("search") == 1 }
        first.cancelAndJoin()
        assertEquals(listOf("search:ma"), gateway.cancelled.toList())
    }

    // ---- threads

    private class CountingDispatcher(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        val from = CopyOnWriteArrayList<String>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            from += Thread.currentThread().name
            delegate.dispatch(context, block)
        }
    }

    @Test fun refreshAndLoadMoreDoTheirWorkOnTheIoDispatcherWhateverTheCaller() = runBlocking {
        val pretendMain = Executors.newSingleThreadExecutor { Thread(it, "pretend-main") }.asCoroutineDispatcher()
        try {
            val io = CountingDispatcher(Dispatchers.IO)
            val other = LibraryRepository(gateway, db, appScope, io, { now }, log)
            gateway.viewsFor = { listOf(view("a"), view("b")) }
            withContext(pretendMain) { other.refresh(s1u1, RefreshTarget.Home) }
            val fromCaller = io.from.count { it.startsWith("pretend-main") }
            assertTrue("the caller's thread handed work to io $fromCaller times", fromCaller <= 1) // one hop in, not one per row
            io.from.clear()
            gateway.childPage = { _, start, _ -> Page(films("x"), start, 500, start + 100, false) }
            val shape = ListShape.DIRECT
            withContext(pretendMain) { other.refresh(s1u1, RefreshTarget.Children(id("p"), shape, nameAsc, noFilter)) }
            io.from.clear()
            withContext(pretendMain) { other.loadMore(s1u1, id("p"), shape, nameAsc, noFilter) }
            assertTrue("loadMore: ${io.from.count { it.startsWith("pretend-main") }}", io.from.count { it.startsWith("pretend-main") } <= 1)
        } finally {
            pretendMain.close()
        }
    }

    // ---- the daily clear-out and the clock

    @Test fun aClockThatWentBackwardsMakesTheClearOutDueNotSkipped() = runBlocking {
        now = t0.toEpochMilli() + Duration.ofDays(40).toMillis()
        library.writePage(s1u1, "old-list", 0, films("x"), 1, t0, fetchStartedAtMs = 0)
        log.last = now + Duration.ofHours(1).toMillis() // "the last run" is in the future: the clock moved back
        gateway.failAll = AppError.Kind.OFFLINE
        repo.home(s1u1).first5s()
        settle()
        assertEquals(1, log.records.size)
        assertEquals(0, count("list_entries", "listKey = 'old-list'"))
    }

    // ---- after the clear-out, under a live collector

    @Test fun aListWhoseSavedStateWasClearedOutUnderALiveCollectorIsStaleUnknownNotASpinner() = runBlocking {
        gateway.resumeFor = { films("a") }
        val seen = CopyOnWriteArrayList<Freshness>()
        val collector = launch { repo.home(s1u1).collect { seen += it.resume.freshness } }
        until { seen.lastOrNull() == Freshness.Fresh }
        advance(Duration.ofDays(40)) // the screen stays open; the daily clear-out then removes the old list
        repo.item(s1u1, id("z")).first5s() // any use of the library starts the clear-out
        settle()
        until { seen.last() == Freshness.Stale(AppError.Kind.UNKNOWN) }
        collector.cancelAndJoin()
    }

    // ---- signing in again

    @Test fun afterSigningInAgainTheListRefreshesAtOnceAndIsFresh() = runBlocking {
        gateway.failOps["resume"] = AppError.Kind.UNAUTHORISED
        repo.home(s1u1).first5s { it.resume.freshness == Freshness.Stale(AppError.Kind.UNAUTHORISED) }
        settle()
        gateway.failOps.clear()
        gateway.resumeFor = { films("a") }
        repo.forget(S1, U1) // what the store announces when the account is signed in again
        repo.home(s1u1).first5s { it.resume.freshness == Freshness.Fresh && it.resume.items.isNotEmpty() }
        assertEquals(2, gateway.count("resume"))
    }

    // ---- a raw database failure on the seam

    @Test fun aRawDatabaseFailureInLoadMoreSearchOrRefreshIsAnAppErrorUnknown() = runBlocking {
        // The tables go missing: Room then fails with a raw SQLiteException (closing the database would cancel the call instead).
        sql("DROP TABLE list_state")
        sql("DROP TABLE visible_items")
        sql("DROP TABLE views")
        gateway.failAll = AppError.Kind.OFFLINE // so search falls back to the (broken) database
        val calls = listOf<suspend () -> Unit>(
            { repo.loadMore(s1u1, id("p"), ListShape.DIRECT, nameAsc, noFilter) },
            { repo.search(s1u1, "x") },
            { repo.refresh(s1u1, RefreshTarget.Home) },
        )
        for ((i, call) in calls.withIndex()) {
            try {
                call()
                fail("call $i should have thrown")
            } catch (e: AppError) {
                assertEquals("call $i", AppError.Kind.UNKNOWN, e.kind)
            }
        }
    }
}
