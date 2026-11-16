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
