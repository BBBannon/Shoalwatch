package app.shoalwatch.ui.admin

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdminPollerTest {
    private val session = Session(S1, U1)
    private val live = MutableStateFlow<Session?>(null)
    private val started = mutableListOf<Long>()
    private var finished = 0
    private var next: Long? = 5_000
    private var loadTime = 0L

    private fun TestScope.poller() = AdminPoller(backgroundScope, live) { s ->
        assertEquals(session, s)
        started += currentTime
        delay(loadTime)
        finished++
        next
    }.also { runCurrent() }

    @Test fun loadsAtOnceThenAfterTheReturnedDelay() = runTest {
        poller()
        live.value = session
        runCurrent()
        assertEquals(listOf(0L), started)
        advanceTimeBy(4_999)
        assertEquals(1, started.size)
        advanceTimeBy(2)
        assertEquals(listOf(0L, 5_000L), started)
    }

    @Test fun aNullDelayWaitsForAKick() = runTest {
        next = null
        val p = poller()
        live.value = session
        runCurrent()
        advanceTimeBy(600_000)
        assertEquals(1, started.size)
        p.kick()
        runCurrent()
        assertEquals(2, started.size)
    }

    @Test fun aKickDuringAWaitLoadsAtOnceAndRestartsTheWait() = runTest {
        val p = poller()
        live.value = session
        runCurrent()
        advanceTimeBy(2_000)
        p.kick()
        runCurrent()
        assertEquals(listOf(0L, 2_000L), started)
        advanceTimeBy(4_999)
        assertEquals(2, started.size)
        advanceTimeBy(2)
        assertEquals(listOf(0L, 2_000L, 7_000L), started)
    }

    @Test fun aKickWhileNotLiveIsNotRemembered() = runTest {
        next = null
        val p = poller()
        p.kick()
        runCurrent()
        assertEquals(0, started.size)
        live.value = session
        runCurrent()
        assertEquals("one load for going live, none for the old kick", 1, started.size)
    }

    @Test fun goingNotLiveCancelsALoadInFlightAndNothingLoadsAfter() = runTest {
        loadTime = 1_000
        poller()
        live.value = session
        runCurrent()
        advanceTimeBy(500)
        live.value = null
        advanceTimeBy(60_000)
        assertEquals(1, started.size)
        assertEquals("the load in flight was cancelled", 0, finished)
        live.value = session
        runCurrent()
        assertEquals("live again loads at once", 2, started.size)
    }

    @Test fun loadsNeverOverlapAndExtraKicksMerge() = runTest {
        loadTime = 1_000
        next = null
        val p = poller()
        live.value = session
        runCurrent()
        repeat(3) { p.kick() }
        advanceTimeBy(500)
        assertEquals(1, started.size)
        advanceTimeBy(600)
        assertEquals("the next load starts only after the first ended", listOf(0L, 1_000L), started)
        advanceTimeBy(10_000)
        assertEquals("three kicks became one load", 2, started.size)
    }
}
