package app.shoalwatch.data.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.impl.WorkManagerImpl
import androidx.work.impl.model.WorkSpec
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.shoalwatch.S1
import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.ui.address
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** When sending happens: each change kicks and (re)places one unique job; start kicks what waits; a server coming back kicks its own profiles. */
class SyncSchedulerTest : SyncSenderTestBase() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var workManager: WorkManager
    private val connections = FakeConnections()

    @Before fun initialiseWork() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        workManager = WorkManager.getInstance(context)
    }

    private fun TestScope.scheduler() = SyncScheduler(workManager, sender, outbox, OnlineEvents(connections, outbox.serversWithWork(), backgroundScope), backgroundScope)

    private fun infos(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(SyncScheduler.WORK_NAME).get()

    private fun pending() = infos().filter { !it.state.isFinished }

    private fun spec(info: WorkInfo): WorkSpec = WorkManagerImpl.getInstance(context).workDatabase.workSpecDao().getWorkSpec(info.id.toString())!!

    /** Waits in real time (the sender runs on its own threads), letting the test dispatcher run meanwhile. */
    private fun TestScope.settle(what: String, done: () -> Boolean) {
        repeat(2_000) {
            runCurrent()
            if (done()) return
            Thread.sleep(5)
        }
        throw AssertionError("timed out: $what")
    }

    private fun callsFor(s: Session) = gateway.calls.count { it.session == s }

    @Test fun aChangeKicksAndEnqueuesOneUniqueJobOnAnyNetworkWithNoInputData() = runTest {
        enqueue(s1u1, favourite("a"))
        scheduler().changed(s1u1)
        settle("sent") { count("sync_outbox") == 0 }
        val job = pending().single()
        assertEquals(NetworkType.CONNECTED, job.constraints.requiredNetworkType)
        val spec = spec(job)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(30_000L, spec.backoffDelayDuration)
        assertEquals(Data.EMPTY, spec.input)
        assertFalse("not expedited", spec.expedited)
    }

    @Test fun aSecondChangeReplacesTheJob() = runTest {
        val scheduler = scheduler()
        scheduler.changed(s1u1)
        val first = pending().single().id
        scheduler.changed(s1u1)
        val second = pending().single().id
        assertNotEquals(first, second)
        val old = workManager.getWorkInfoById(first).get()
        assertTrue("the first is gone (REPLACE prunes it) or cancelled", old == null || old.state == WorkInfo.State.CANCELLED)
    }

    @Test fun startKicksEveryProfileWithRowsAndSchedules() = runTest {
        enqueue(s1u1, favourite("a"))
        enqueue(s1u2, favourite("b"))
        enqueue(s2u1, favourite("c"))
        scheduler().start().join()
        settle("all sent") { count("sync_outbox") == 0 }
        assertEquals(listOf(1, 1, 1), listOf(s1u1, s1u2, s2u1).map(::callsFor))
        assertEquals(1, pending().size)
    }

    @Test fun startWithNothingWaitingSchedulesNothing() = runTest {
        scheduler().start().join()
        runCurrent()
        assertTrue(infos().isEmpty())
        assertTrue(gateway.calls.isEmpty())
    }

    @Test fun aServerComingOnlineKicksOnlyItsOwnProfilesOnce() = runTest {
        enqueue(s1u1, favourite("a"))
        enqueue(s2u1, favourite("b"))
        gateway.failNext("favourite", AppError.Kind.OFFLINE, times = 2)
        val one = connections.stateFor(S1) // Checking
        val two = connections.stateFor(s2u1.serverId)
        scheduler().start().join()
        settle("both tried and watched") { gateway.calls.size == 2 && one.subscriptionCount.value > 0 && two.subscriptionCount.value > 0 }
        settle("both drains have ended") { !sender.isDraining(s1u1) && !sender.isDraining(s2u1) } // a signal, not a sleep (review M8)
        assertEquals(2, gateway.calls.size)
        one.value = Connection.Online(address("https://den.example"), isHome = false)
        settle("the first server's row went and its drain ended") { count("sync_outbox", "serverId = '$S1'") == 0 && !sender.isDraining(s1u1) }
        assertEquals("one more send, for the server that came back", listOf(2, 1), listOf(s1u1, s2u1).map(::callsFor))
        assertEquals(1, count("sync_outbox"))
    }

    /**
     * Carried from Tasks 13-14 review M4: a request that times out makes ServerApi re-probe, and the probe's new Online is a
     * cameOnline, which kicked the head row again at once, for as long as the link stayed that slow. After a NETWORK or TIMEOUT,
     * that server's reconnection kick is held for 1 min, then 2 min, and so on (the sender's clock); a change still sends at
     * once, and a request the server answers ends the hold, so the next reconnection kicks again.
     */
    @Test fun aTimeoutHoldsTheReprobeKickWithABackOffButNotAChange() = runTest {
        enqueue(s1u1, favourite("a"))
        val state = connections.stateFor(S1)
        val online = { n: Int -> Connection.Online(address("http://192.168.1.$n:8096"), isHome = true) }
        state.value = online(10)
        gateway.failNext("favourite", AppError.Kind.TIMEOUT, times = 2)
        val scheduler = scheduler()
        scheduler.start().join()
        settle("tried once and watched") { gateway.calls.size == 1 && state.subscriptionCount.value > 0 && !sender.isDraining(s1u1) }
        var n = 10
        fun reprobe() { // what refreshIfCurrent does after a failed request: Checking, then a new Online; waits until it is handled
            val handled = scheduler.onlineHandled.get()
            state.value = Connection.Checking(online(n))
            runCurrent()
            state.value = online(++n)
            settle("handled") { scheduler.onlineHandled.get() == handled + 1 }
            settle("no drain running") { !sender.isDraining(s1u1) } // a kick, had there been one, has sent by now
        }
        reprobe()
        assertEquals("inside the 1 min hold", 1, gateway.calls.size)
        clock += 60_001
        reprobe() // kicks: times out again, hold 2 min
        assertEquals(2, gateway.calls.size)
        clock += 60_001
        reprobe()
        assertEquals("the hold doubled", 2, gateway.calls.size)
        enqueue(s1u1, favourite("b"))
        scheduler.changed(s1u1) // a tick is never held
        settle("both sent at once") { count("sync_outbox") == 0 }
        assertEquals(4, gateway.calls.size)
        settle("no longer watched (no rows)") { state.subscriptionCount.value == 0 }
        enqueue(s1u1, favourite("c"))
        gateway.failNext("favourite", AppError.Kind.OFFLINE)
        scheduler.changed(s1u1)
        settle("tried and watched again") { gateway.calls.size == 5 && !sender.isDraining(s1u1) && state.subscriptionCount.value > 0 }
        reprobe() // the answer ended the hold and OFFLINE starts none: this reconnection kicks
        assertEquals(0, count("sync_outbox"))
        assertEquals(6, gateway.calls.size)
    }

    @Test fun aSignInSendsThatProfilesRows() = runTest {
        signedOut += s1u2
        enqueue(s1u2, favourite("a"))
        val scheduler = scheduler()
        scheduler.start().join()
        signedOut -= s1u2
        scheduler.signedIn(s1u2.serverId, s1u2.userId)
        settle("sent") { count("sync_outbox") == 0 }
        assertEquals(1, callsFor(s1u2))
        assertEquals(1, pending().size)
    }
}
