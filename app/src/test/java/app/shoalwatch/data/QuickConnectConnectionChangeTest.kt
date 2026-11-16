package app.shoalwatch.data

import app.shoalwatch.core.AppError
import app.shoalwatch.testId
import app.shoalwatch.data.servers.AuthResult
import app.shoalwatch.data.servers.ServerInfo
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.servers.AuthRepository
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.QuickConnectState
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.security.SecretBox
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.KeyGenerator

/** Quick Connect stops, and sends no further secret, as soon as the connection it started on is no longer the verified one (review F2). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class QuickConnectConnectionChangeTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val device = DeviceIdentity("dev-1", "Pixel", "Shoalwatch", "0.1")
    private val gateway = FakeGateway()
    private lateinit var db: AppDatabase
    private lateinit var store: ServerStore
    private lateinit var repo: AuthRepository
    private val serverId = testId("s1")
    private val home = ServerAddress.parse("https://home.example")
    private val away = ServerAddress.parse("https://away.example")
    private val connections = StubConnections { Connection.Online(home, isHome = true) }

    @Before fun open() {
        db = inMemoryDb()
        store = ServerStore(db, SecretBox { key })
        repo = AuthRepository(gateway, store, device, connections)
        gateway.info = { ServerInfo(serverId, "Den", "10.10.3", it, ServerInfo.Compatibility.SUPPORTED) }
        runBlocking { store.warmTokens(); store.upsertServer(Server(serverId, "Den", "10.10.3", home, away, false, null, null, emptyMap())) }
    }

    @After fun close() = db.close()

    /** The store and Room run on real threads, so let the flow reach its first poll before virtual time moves. */
    private fun kotlinx.coroutines.test.TestScope.startAndAwaitCode(states: MutableList<QuickConnectState>) {
        val route = runBlocking { repo.signInRoute(serverId)!! }
        backgroundScope.launch { repo.quickConnect(route).collect { states += it } }
        val deadline = System.nanoTime() + 5_000_000_000
        while (states.none { it is QuickConnectState.AwaitingApproval }) {
            testScheduler.runCurrent()
            check(System.nanoTime() < deadline) { "Quick Connect never showed its code" }
            Thread.sleep(5)
        }
    }

    /** Starts polling, lets it poll a few times, applies [change], and returns what the flow emitted. */
    private fun kotlinx.coroutines.test.TestScope.pollThenChange(change: () -> Unit): List<QuickConnectState> {
        gateway.polls = { false }
        val states = mutableListOf<QuickConnectState>()
        startAndAwaitCode(states)
        advanceTimeBy(9_000)
        assertTrue("polling before the change", gateway.pollCount >= 2)
        change()
        advanceTimeBy(1)
        val before = gateway.pollCount
        val calls = gateway.calls.size
        advanceTimeBy(120_000)
        assertEquals("no poll after the change", before, gateway.pollCount)
        assertEquals("no gateway call at all after the change", calls, gateway.calls.size)
        return states
    }

    /** Lets the real-thread work after a poll (store reads, saving) finish, then runs what it released. */
    private fun kotlinx.coroutines.test.TestScope.settle() {
        repeat(20) { testScheduler.runCurrent(); Thread.sleep(10) }
    }

    private fun assertChanged(states: List<QuickConnectState>) {
        val last = states.last() as QuickConnectState.Failed
        assertEquals(AppError.Kind.CONNECTION_CHANGED, last.error.kind)
        assertFalse(states.any { it is QuickConnectState.Success })
    }

    @Test fun aNetworkChangeToCheckingStopsPollingAtOnce() = runTest {
        assertChanged(pollThenChange { connections.set(serverId, Connection.Checking(Connection.Online(home, true))) })
    }

    @Test fun goingOfflineStopsPolling() = runTest {
        assertChanged(pollThenChange { connections.set(serverId, Connection.Offline()) })
    }

    @Test fun onlineOnADifferentAddressStopsPolling() = runTest {
        assertChanged(pollThenChange { connections.set(serverId, Connection.Online(away, isHome = false)) })
    }

    @Test fun anApprovalThatArrivesAfterTheChangeSendsNoSecret() = runTest {
        gateway.polls = { n -> n >= 1 } // approved on the second poll
        gateway.afterPoll = { if (it == 1) connections.set(serverId, Connection.Checking()) }
        val states = mutableListOf<QuickConnectState>()
        startAndAwaitCode(states)
        advanceTimeBy(30_000)
        settle()
        assertFalse("authQc" in gateway.calls)
        assertEquals(AppError.Kind.CONNECTION_CHANGED, (states.last() as QuickConnectState.Failed).error.kind)
        assertNull(runBlocking { store.active() })
    }

    @Test fun anUnchangedConnectionKeepsPollingAndSignsIn() = runTest {
        gateway.polls = { n -> n >= 2 }
        gateway.auth = { AuthResult(testId("u1"), "ben", "tok-1", serverId) }
        val states = mutableListOf<QuickConnectState>()
        startAndAwaitCode(states)
        advanceTimeBy(30_000)
        settle()
        assertTrue(states.last() is QuickConnectState.Success)
    }
}
