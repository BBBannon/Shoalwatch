package app.shoalwatch.data

import app.shoalwatch.data.servers.Connection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The automatic re-probe of an `Offline` server runs only while the app is in the foreground, never shows `Checking` (so the
 * connection chip does not re-announce "Offline" on every failed try), and is never started for a server nobody asked about.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AddressSelectorForegroundTest : AddressSelectorTestBase() {
    private fun TestScope.at(ms: Long) { advanceTimeBy(ms - currentTime); runCurrent() }

    private fun TestScope.watch(flow: Flow<Connection>) { backgroundScope.launch { flow.collect {} }; runCurrent() }

    @Test fun inTheBackgroundAnOfflineServerIsNeverReprobedHoweverLong() = runTest {
        foreground.value = false
        val s = selector()
        gateway.behaviour[home] = fail
        watch(s.connection(server(a = null)))
        at(3_600_000)
        assertEquals("only the first probe", 1, gateway.calls.size)
    }

    @Test fun goingToTheBackgroundStopsTheBackoff() = runTest {
        val s = selector()
        gateway.behaviour[home] = fail
        watch(s.connection(server(a = null)))
        at(5_000)
        assertEquals(2, gateway.calls.size)
        foreground.value = false
        at(3_600_000)
        assertEquals("nothing while in the background", 2, gateway.calls.size)
    }

    @Test fun comingBackToTheForegroundProbesAtOnceAndStartsTheBackoffAgainAtFiveSeconds() = runTest {
        val s = selector()
        gateway.behaviour[home] = fail
        watch(s.connection(server(a = null)))
        at(5_000)
        at(15_000) // three probes so far: the next wait would be 20 s
        foreground.value = false
        at(1_000_000)
        assertEquals(3, gateway.calls.size)
        foreground.value = true
        runCurrent()
        assertEquals("probed at once on coming back", 4, gateway.calls.size)
        at(1_000_000 + 4_999)
        assertEquals(4, gateway.calls.size)
        at(1_000_000 + 5_000)
        assertEquals("then 5 s, not 20 s", 5, gateway.calls.size)
    }

    @Test fun comingBackWhileOnlineSendsNothing() = runTest {
        val s = selector()
        watch(s.connection(server(a = null)))
        assertEquals(1, gateway.calls.size)
        foreground.value = false
        at(10_000)
        foreground.value = true
        at(20_000)
        assertEquals(1, gateway.calls.size)
    }

    @Test fun anAutomaticReprobeThatFailsLeavesTheSameOfflineWithoutShowingChecking() = runTest {
        val s = selector()
        gateway.behaviour[home] = fail
        val flow = s.connection(server(a = null))
        runCurrent()
        val seen = mutableListOf<Connection>()
        backgroundScope.launch { flow.collect { seen += it } }
        runCurrent()
        at(75_000) // four automatic re-probes, all failing
        assertEquals(5, gateway.calls.size)
        assertEquals("one Offline, never Checking in between", listOf<Connection>(Connection.Offline()), seen)
    }

    @Test fun aUserRefreshStillShowsChecking() = runTest {
        val s = selector()
        gateway.behaviour[home] = fail
        val flow = s.connection(server(a = null))
        watch(flow)
        s.refresh("abc123")
        assertEquals(Connection.Checking(), flow.value)
    }

    @Test fun anAutomaticReprobeThatSucceedsIsPublished() = runTest {
        val s = selector()
        gateway.behaviour[home] = fail
        val flow = s.connection(server(a = null))
        watch(flow)
        gateway.behaviour.remove(home)
        at(5_000)
        assertEquals(Connection.Online(home, true), flow.value)
    }

    @Test fun refreshingAServerNobodyAskedAboutStartsNothing() = runTest {
        val s = selector()
        source.put(server(a = null))
        s.refresh("abc123")
        s.refreshIfOffline("abc123")
        settle()
        assertEquals(0, source.reads)
        assertEquals(0, gateway.calls.size)
    }

    @Test fun aRemovedServerIsNotProbedAgainByARefreshIfOffline() = runTest {
        val s = selector()
        gateway.behaviour[home] = fail
        s.connection(server(a = null))
        runCurrent()
        s.forget("abc123")
        source.remove("abc123")
        val reads = source.reads
        s.refreshIfOffline("abc123")
        at(600_000)
        assertEquals("no entry is made again for it", reads, source.reads)
    }

    @Test fun aServerNoLongerSavedIsNotReprobedOnTheBackoff() = runTest {
        val s = selector()
        gateway.behaviour[home] = fail
        val flow = s.connection(server(a = null))
        watch(flow)
        source.remove("abc123") // still watched, but gone from the store: Offline, nothing to probe
        runCurrent()
        assertEquals(Connection.Offline(), flow.value)
        val reads = source.reads
        at(600_000)
        assertEquals("the store is not read again and again", reads, source.reads)
    }
}
