package app.shoalwatch.data.downloads

import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.DownloadTestBase
import app.shoalwatch.data.db.downloads.AutoNextRequestEntity
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.sync.OnlineEvents
import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.ui.address
import app.shoalwatch.ui.browse.FakeDownloads
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Auto-download next on in-memory Room with [FakeDownloads] (what was prepared and started) and virtual time for the trigger.
 * The anchor is a COMPLETE episode [a] downloaded in HIGH; [b] is another profile on the same server.
 */
class AutoDownloadNextTest : DownloadTestBase() {
    private val a get() = s1u1
    private val b get() = s1u2
    private val downloads = FakeDownloads()
    private lateinit var prefs: DownloadPrefs
    private var meteredNow = false
    private val connections = FakeConnections()
    private val watched = MutableStateFlow(emptySet<String>())
    private val targets = mutableListOf<Pair<DownloadTarget, DownloadQuality>>()
    private val e1 get() = ep("e1", 1, 1)
    private val e2 get() = ep("e2", 1, 2)

    /** What prepare answers: [items] planned, with [enough] space; or [failure] thrown. */
    private var items = 2
    private var enough = true
    private var failure: AppError? = null
    /** Every planned item is already on the phone (joined at 0 bytes). */
    private var shareOnly = false

    @Before fun setUp() {
        prefs = DownloadPrefs(ApplicationProvider.getApplicationContext<android.content.Context>().getSharedPreferences("auto-next-test", 0).also { it.edit().clear().commit() })
        downloads.plans = { s, target, quality ->
            targets += target to quality
            failure?.let { throw it }
            DownloadPlan(
                s, quality, (1..items).map { PlannedItem(id("next$it"), quality, if (shareOnly) 0 else 100, shareOnly, true) }, emptyList(), 0,
                if (shareOnly) 0 else 100L * items, 1_000,
                enough, StorageKind.INTERNAL, null, false,
            )
        }
        for (s in listOf(a, b)) seen(s, series, season1, e1, e2)
        downloaded(a, e1)
    }

    private fun downloaded(s: Session, item: Item) = runBlocking {
        store.add(s, listOf(newDownload(item, DownloadQuality.HIGH)))
        store.update(DownloadKey(s.serverId, item.id)) { it.copy(state = DownloadState.COMPLETE) }
        store.markFetcher(DownloadKey(s.serverId, item.id), s.userId)
    }

    private fun TestScope.auto() = AutoDownloadNext(
        db.housekeepingDao(), downloads, prefs, { meteredNow }, OnlineEvents(connections, watched, backgroundScope), backgroundScope,
        { clock }, StandardTestDispatcher(testScheduler),
    )

    private fun autoNext(s: Session, count: Int) = runBlocking {
        db.housekeepingDao().insertProfileIfMissing(s.serverId, s.userId)
        db.housekeepingDao().updateAutoNext(s.serverId, s.userId, count)
    }

    private fun request(s: Session = a): AutoNextRequestEntity? = runBlocking { db.housekeepingDao().autoNextRequest(s.serverId, s.userId, series.id) }

    private fun started() = downloads.calls.filter { it.startsWith("start:") }

    @Test fun offDoesNothing() = runTest {
        auto().onWatched(a, e1.id).join()
        assertEquals(emptyList<String>(), downloads.calls)
        assertNull(request())
    }

    @Test fun twoPlansTheNextTwoInTheAnchorsQualityAndStarts() = runTest {
        autoNext(a, 2)
        auto().onWatched(a, e1.id).join()
        assertEquals(listOf(DownloadTarget.NextUnwatched(series.id, e1.id, 2) to DownloadQuality.HIGH), targets)
        assertEquals(listOf("prepare", "start:2"), downloads.calls)
        assertNull("started: the request goes", request())
    }

    @Test fun aDefaultQualityWins() = runTest {
        autoNext(a, 1)
        prefs.setDefaultQuality(DownloadQuality.LOW)
        auto().onWatched(a, e1.id).join()
        assertEquals(DownloadQuality.LOW, targets.single().second)
    }

    @Test fun theAnchorsQualityIsKeptAfterItIsReleased() = runTest {
        autoNext(a, 1)
        val auto = auto()
        auto.remember(a, e1.id) // before the release deletes the row
        runBlocking { db.downloadDao().delete(a.serverId, e1.id) }
        auto.onWatched(a, e1.id).join()
        assertEquals(DownloadQuality.HIGH, targets.single().second)
    }

    @Test fun aRepeatOfTheSameWatchPlansOnce() = runTest {
        autoNext(a, 2)
        val auto = auto()
        auto.remember(a, e1.id) // the press
        auto.onWatched(a, e1.id).join()
        auto.remember(a, e1.id) // the stop
        auto.onWatched(a, e1.id).join()
        assertEquals(listOf("prepare", "start:2"), downloads.calls)
        assertNull(request())
    }

    @Test fun offlineKeepsTheRequestAndRunsItWhenTheServerComesOnline() = runTest {
        autoNext(a, 2)
        failure = AppError.of(AppError.Kind.OFFLINE)
        connections.stateFor(a.serverId).value = Connection.Offline()
        watched.value = setOf(a.serverId)
        val auto = auto()
        auto.start()
        runCurrent()
        auto.onWatched(a, e1.id).join()
        assertNotNull("kept", request())
        assertEquals(0, request()!!.tries)
        failure = null
        connections.stateFor(a.serverId).value = Connection.Online(address("http://192.168.1.10:8096"), isHome = true)
        eventually { started().isNotEmpty() }
        assertEquals(listOf("start:2"), started())
        eventually { request() == null }
    }

    /**
     * Tasks 13-14 review M4: a request that times out makes ServerApi re-probe, and the probe's new Online comes back as
     * cameOnline, so without a pause it would plan again at once, for as long as the link stays that slow. After a NETWORK or
     * TIMEOUT failure, that server's cameOnline is ignored for 1 min, then 2 min, and so on (virtual clock); a run that gets an
     * answer ends the pause.
     */
    @Test fun aTimeoutPausesTheReprobeTriggerWithABackOff() = runTest {
        autoNext(a, 2)
        val online = { n: Int -> Connection.Online(address("http://192.168.1.$n:8096"), isHome = true) }
        connections.stateFor(a.serverId).value = online(10)
        watched.value = setOf(a.serverId)
        val auto = auto()
        auto.start()
        runCurrent()
        var n = 10
        fun reprobe() { // what refreshIfCurrent does after a failed request: Checking, then a new Online; waits until it is handled
            val handled = auto.onlineHandled.get()
            connections.stateFor(a.serverId).value = Connection.Checking(online(n))
            runCurrent()
            connections.stateFor(a.serverId).value = online(++n)
            eventually { auto.onlineHandled.get() == handled + 1 }
        }
        fun prepares() = downloads.calls.count { it == "prepare" }
        failure = AppError.of(AppError.Kind.TIMEOUT)
        auto.onWatched(a, e1.id).join() // #1: times out, pause 1 min
        assertEquals(1, prepares())
        reprobe() // ignored
        assertEquals("inside the pause", 1, prepares())
        clock += 60_001
        reprobe() // #2: times out again, pause 2 min
        assertEquals(2, prepares())
        clock += 60_001
        reprobe() // ignored: inside the 2 min
        assertEquals("the pause doubled", 2, prepares())
        clock += 60_000
        failure = null
        reprobe() // #3: answers, starts
        eventually { started().isNotEmpty() }
        assertEquals("only the runs outside the pause planned", 3, prepares())
        assertNull(request())
    }

    /**
     * Task 15 concern 2 (as e8693dd for sync): a NETWORK failure while the server is really gone sets the pause, and the probe
     * then reads Offline. The server's return after Offline is a real reconnection and is never held by the pause.
     */
    @Test fun aReconnectionAfterOfflineIsNeverHeldByThePause() = runTest {
        autoNext(a, 2)
        connections.stateFor(a.serverId).value = Connection.Online(address("http://192.168.1.10:8096"), isHome = true)
        watched.value = setOf(a.serverId)
        val auto = auto()
        auto.start()
        runCurrent()
        failure = AppError.of(AppError.Kind.NETWORK)
        auto.onWatched(a, e1.id).join() // fails NETWORK: pause 1 min
        assertNotNull("kept", request())
        connections.stateFor(a.serverId).value = Connection.Checking(null)
        runCurrent()
        connections.stateFor(a.serverId).value = Connection.Offline() // the server is really gone
        runCurrent()
        failure = null
        val handled = auto.onlineHandled.get()
        connections.stateFor(a.serverId).value = Connection.Online(address("http://192.168.1.10:8096"), isHome = true) // back, inside the minute
        eventually { auto.onlineHandled.get() == handled + 1 }
        eventually { started().isNotEmpty() }
        assertEquals(listOf("start:2"), started())
        eventually { request() == null }
    }

    @Test fun meteredWithMobileDataOffWaitsAndMobileDataOnRuns() = runTest {
        autoNext(a, 2)
        meteredNow = true
        val auto = auto()
        auto.onWatched(a, e1.id).join()
        assertEquals(emptyList<String>(), downloads.calls)
        assertNotNull(request())
        meteredNow = false // on Wi-Fi now
        auto.runPending(a)
        assertEquals(listOf("prepare", "start:2"), downloads.calls)
        assertNull(request())
    }

    @Test fun aMeteredCheckThatFailsCountsAsMetered() = runTest {
        autoNext(a, 2)
        val auto = AutoDownloadNext(
            db.housekeepingDao(), downloads, prefs, { error("no network service") }, OnlineEvents(connections, watched, backgroundScope),
            backgroundScope, { clock }, StandardTestDispatcher(testScheduler),
        )
        auto.onWatched(a, e1.id).join()
        assertEquals(emptyList<String>(), downloads.calls)
        prefs.setMobileData(true)
        auto.runPending(a)
        assertEquals(listOf("prepare", "start:2"), downloads.calls)
    }

    /** Tasks 13-14 review M3: the next episodes are all on the phone already, so the space reserve is no reason to wait. */
    @Test fun aPlanOfOnlyCopiesAlreadyHereIsStartedEvenWithLittleSpace() = runTest {
        autoNext(a, 2)
        shareOnly = true
        enough = false // the planner's reserve (1 GiB internal) is more than is free
        auto().onWatched(a, e1.id).join()
        assertEquals(listOf("start:2"), started())
        assertNull("no false \"not enough free space\": the request goes", request())
        assertEquals(false, db.housekeepingDao().observeAutoNextBlocked(a.serverId, a.userId).first())
    }

    @Test fun notEnoughSpaceStartsNothingAndSetsBlockedAt() = runTest {
        autoNext(a, 2)
        enough = false
        clock = 5_000
        auto().onWatched(a, e1.id).join()
        assertEquals(emptyList<String>(), started())
        assertEquals(5_000L, request()?.blockedAt)
        assertEquals(0, request()?.tries)
        enough = true
        auto().runPending(a)
        assertEquals(listOf("start:2"), started())
        assertNull(request())
    }

    @Test fun nothingToFetchDropsTheRequest() = runTest {
        autoNext(a, 2)
        items = 0
        auto().onWatched(a, e1.id).join()
        assertEquals(emptyList<String>(), started())
        assertNull(request())
    }

    @Test fun fiveFailuresDropIt() = runTest {
        autoNext(a, 2)
        failure = AppError.of(AppError.Kind.SERVER_ERROR)
        val auto = auto()
        auto.onWatched(a, e1.id).join()
        assertEquals(1, request()?.tries)
        repeat(3) { auto.runPending(a) }
        assertEquals(4, request()?.tries)
        auto.runPending(a)
        assertNull("dropped after ${AutoDownloadNext.MAX_TRIES}", request())
        assertEquals(5, downloads.calls.count { it == "prepare" })
    }

    @Test fun aSecondWatchOfTheSameSeriesReplacesTheAnchor() = runTest {
        autoNext(a, 2)
        failure = AppError.of(AppError.Kind.OFFLINE)
        downloaded(a, e2)
        val auto = auto()
        auto.onWatched(a, e1.id).join()
        clock += 1_000
        auto.onWatched(a, e2.id).join()
        assertEquals(e2.id, request()?.anchorItemId)
        assertEquals(1, count("auto_next_requests", "1 = 1"))
    }

    @Test fun removingTheAccountRemovesTheRequest() = runTest {
        autoNext(a, 2)
        failure = AppError.of(AppError.Kind.OFFLINE)
        auto().onWatched(a, e1.id).join()
        assertNotNull(request())
        servers.removeAccount(a.serverId, a.userId)
        assertNull(request())
    }

    @Test fun anotherProfilesSettingNeverApplies() = runTest {
        autoNext(b, 5)
        auto().onWatched(a, e1.id).join()
        assertEquals("a's own setting is Off", emptyList<String>(), downloads.calls)
        assertNull(request(a))
        assertNull(request(b))
    }

    @Test fun aDownloadThisProfileMayNotPlayIsNoAnchor() = runTest {
        autoNext(b, 2)
        auto().onWatched(b, e1.id).join() // a's own copy, sharing off: b may not play it
        assertEquals(emptyList<String>(), downloads.calls)
        assertNull(request(b))
    }

    @Test fun turnedOffSinceDropsTheWaitingRequest() = runTest {
        autoNext(a, 2)
        failure = AppError.of(AppError.Kind.OFFLINE)
        val auto = auto()
        auto.onWatched(a, e1.id).join()
        autoNext(a, 0)
        failure = null
        auto.runPending(a)
        assertEquals(listOf("prepare"), downloads.calls)
        assertNull(request())
    }

    /** Runs what is due and waits in real time (Room answers on its own threads) for [done], up to five seconds. */
    private fun TestScope.eventually(done: () -> Boolean) {
        val end = System.nanoTime() + 5_000_000_000L
        while (true) {
            runCurrent()
            if (done()) return
            check(System.nanoTime() < end) { "timed out" }
            Thread.sleep(10)
        }
    }
}
