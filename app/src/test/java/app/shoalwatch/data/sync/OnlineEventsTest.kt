package app.shoalwatch.data.sync

import app.shoalwatch.data.servers.Connection
import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.ui.address
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** "This server just came online": only a change INTO Online counts, and only for the servers being watched. */
class OnlineEventsTest {
    private val connections = FakeConnections()
    private val servers = MutableStateFlow(setOf("one", "two"))
    private val home = Connection.Online(address("http://192.168.1.10:8096"), isHome = true)
    private val away = Connection.Online(address("https://den.example"), isHome = false)

    private fun state(id: String) = connections.stateFor(id)

    private fun TestScope.collected(): List<String> {
        val events = OnlineEvents(connections, servers, backgroundScope)
        val got = mutableListOf<String>()
        backgroundScope.launch { events.cameOnline.collect { got += it } }
        runCurrent()
        return got
    }

    @Test fun onlineStayingTheSameEmitsNothing() = runTest {
        state("one").value = home
        val got = collected()
        state("one").value = home
        runCurrent()
        assertEquals(emptyList<String>(), got)
    }

    @Test fun offlineToOnlineEmitsOnceForThatServerOnly() = runTest {
        state("one").value = Connection.Offline()
        state("two").value = Connection.Offline()
        val got = collected()
        state("one").value = home
        runCurrent()
        assertEquals(listOf("one"), got)
    }

    @Test fun checkingToOnlineEmitsOnce() = runTest {
        val got = collected() // FakeConnections starts at Checking
        state("one").value = home
        runCurrent()
        state("one").value = Connection.Checking(previous = home)
        runCurrent()
        state("one").value = home
        runCurrent()
        assertEquals(listOf("one", "one"), got)
    }

    /** Carried from review M4: a re-probe (Checking, then Online) is not after Offline; Offline on the way is, once. */
    @Test fun aReconnectionSaysWhetherTheServerWasOfflineSinceItWasLastOnline() = runTest {
        state("one").value = home
        val events = OnlineEvents(connections, servers, backgroundScope)
        val got = mutableListOf<OnlineEvents.Reconnection>()
        backgroundScope.launch { events.reconnections.collect { got += it } }
        runCurrent()
        for (next in listOf(Connection.Checking(home), home, Connection.Checking(home), Connection.Offline(), Connection.Checking(), home, away)) {
            state("one").value = next
            runCurrent()
        }
        assertEquals(listOf(false, true, false).map { OnlineEvents.Reconnection("one", it) }, got)
    }

    @Test fun onlineOnAnotherAddressEmitsOnce() = runTest {
        state("one").value = home
        val got = collected()
        state("one").value = away
        runCurrent()
        assertEquals(listOf("one"), got)
    }

    @Test fun aServerLeavingTheSetStopsBeingWatched() = runTest {
        state("one").value = Connection.Offline()
        val got = collected()
        assertEquals(1, state("one").subscriptionCount.value)
        servers.value = setOf("two")
        runCurrent()
        assertEquals("no longer watched", 0, state("one").subscriptionCount.value)
        state("one").value = home
        runCurrent()
        assertEquals(emptyList<String>(), got)
    }

    @Test fun aServerJoiningTheSetIsWatchedFromThen() = runTest {
        servers.value = emptySet()
        state("three").value = Connection.Offline()
        val got = collected()
        servers.value = setOf("three")
        runCurrent()
        state("three").value = home
        runCurrent()
        assertEquals(listOf("three"), got)
    }
}
