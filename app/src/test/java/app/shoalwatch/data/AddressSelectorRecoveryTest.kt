package app.shoalwatch.data

import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.net.ServerAddress
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * A server that was unreachable comes back without the app being restarted: a user's own refresh re-probes that server
 * (`refresh(serverId)`), and while something is watching an `Offline` server for ordinary unreachability it is re-probed on a
 * backoff (5 s, doubling to 60 s). A security reason is never retried by itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddressSelectorRecoveryTest : AddressSelectorTestBase() {
    private fun TestScope.at(ms: Long) { advanceTimeBy(ms - currentTime); runCurrent() }

    private fun TestScope.watch(flow: kotlinx.coroutines.flow.Flow<Connection>) { backgroundScope.launch { flow.collect {} }; runCurrent() }

    // --- the user's own refresh ---------------------------------------------------------------------------------------

    @Test fun refreshingOneServerReprobesThatServerAloneAndBringsItBack() = runTest {
        val s = selector()
        val a = server(a = null)
        val bHome = ServerAddress.parse("http://192.168.1.11:8096")
        gateway.behaviour[bHome] = { "bbb" }
        val b = server(h = bHome, id = "bbb", a = null)
        gateway.behaviour[home] = fail
        val flowA = s.connection(a)
        val other = s.awaitOnline(b)!!
        runCurrent()
        assertEquals(Connection.Offline(), flowA.value)
        gateway.behaviour.remove(home)
        val before = gateway.calls.size
        s.refresh("abc123")
        assertEquals("synchronously Checking, so a following awaitOnline waits", Connection.Checking(), flowA.value)
        settle()
        assertEquals(Connection.Online(home, true), flowA.value)
        assertEquals("one probe, for one server", before + 1, gateway.calls.size)
        assertSame("the other server is untouched", other, s.connection("bbb").value)
    }

    @Test fun anAwaitOnlineAfterTheRefreshWaitsForTheNewProbeNotTheOldOffline() = runTest {
        val s = selector()
        val a = server(a = null)
        gateway.behaviour[home] = fail
        s.connection(a)
        runCurrent()
        gateway.behaviour.remove(home)
        s.refresh("abc123")
        assertNotNull(s.awaitOnline("abc123"))
    }

    @Test fun anAwaitOnlineAfterARefreshOfAServerThatIsStillDownIsNull() = runTest {
        val s = selector()
        gateway.behaviour[home] = fail
        s.connection(server(a = null))
        runCurrent()
        s.refresh("abc123")
        assertNull(s.awaitOnline("abc123"))
    }

    // --- recovery without the user ----------------------------------------------------------------------------------

    @Test fun anOfflineServerThatIsWatchedIsReprobedAfterFiveSecondsAndComesBack() = runTest {
        val s = selector()
        gateway.behaviour[home] = fail
        val flow = s.connection(server(a = null))
        watch(flow)
        assertEquals(Connection.Offline(), flow.value)
        gateway.behaviour.remove(home)
        at(4_999)
        assertEquals("not yet", 1, gateway.calls.size)
        at(5_000)
        assertEquals(Connection.Online(home, true), flow.value)
    }

    @Test fun theBackoffDoublesToSixtySecondsAndStopsOnceOnline() = runTest {
        val s = selector()
        gateway.behaviour[home] = fail
        val flow = s.connection(server(a = null))
        watch(flow)
        val probeTimes = listOf(0L, 5_000L, 15_000L, 35_000L, 75_000L, 135_000L, 195_000L)
        probeTimes.forEachIndexed { i, t ->
            at(t)
            assertEquals("probes by t=$t", i + 1, gateway.calls.size)
            if (i + 1 < probeTimes.size) {
                at(probeTimes[i + 1] - 1)
                assertEquals("none just before the next", i + 1, gateway.calls.size)
            }
        }
        gateway.behaviour.remove(home)
        at(255_000)
        assertEquals(Connection.Online(home, true), flow.value)
        val probes = gateway.calls.size
        at(1_000_000)
        assertEquals("no more once Online", probes, gateway.calls.size)
    }

    @Test fun aNetworkChangeStartsTheBackoffAgainFromFiveSeconds() = runTest {
        val s = selector()
        gateway.behaviour[home] = fail
        val flow = s.connection(server(a = null))
        watch(flow)
        at(5_000)
        at(15_000) // three probes so far, the next wait would be 20 s
        assertEquals(3, gateway.calls.size)
        monitor.flow.emit(Unit)
        at(15_000 + 500) // debounce, then the probe for the change
        assertEquals(4, gateway.calls.size)
        at(15_500 + 4_999)
        assertEquals(4, gateway.calls.size)
        at(15_500 + 5_000)
        assertEquals("back to a 5 s wait, not 20 s", 5, gateway.calls.size)
    }

    @Test fun aSecurityReasonIsNeverReprobedByItself() = runTest {
        val s = selector()
        gateway.behaviour[home] = { throw AppError.of(AppError.Kind.TLS_PIN_CHANGED) }
        val flow = s.connection(server(a = null))
        watch(flow)
        assertEquals(Connection.Offline(AppError.Kind.TLS_PIN_CHANGED), flow.value)
        at(600_000)
        assertEquals(1, gateway.calls.size)
    }

    @Test fun nothingIsReprobedWhileNothingIsWatching() = runTest {
        val s = selector()
        gateway.behaviour[home] = fail
        s.connection(server(a = null))
        runCurrent()
        at(600_000)
        assertEquals(1, gateway.calls.size)
    }

    @Test fun reprobingStartsAgainWhenSomethingWatchesAgain() = runTest {
        val s = selector()
        gateway.behaviour[home] = fail
        val flow = s.connection(server(a = null))
        val watcher = backgroundScope.launch { flow.collect {} }
        runCurrent()
        watcher.cancel()
        at(600_000)
        assertEquals("nobody was watching", 1, gateway.calls.size)
        watch(flow)
        at(600_000 + 60_000)
        assertEquals(true, gateway.calls.size > 1)
    }
}
