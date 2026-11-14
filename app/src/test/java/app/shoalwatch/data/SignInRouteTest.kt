package app.shoalwatch.data

import app.shoalwatch.S1
import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.servers.AuthRepository
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.QuickConnectState
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerInfo
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.security.SecretBox
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
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

/**
 * Review I2: credentials (password, Quick Connect) go to an https address of the server whenever one answers as
 * this server; plain http only when it is the server's only address or the https one cannot be reached, and the
 * route says so ([app.shoalwatch.data.servers.SignInRoute.encrypted]) so the screen can warn.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SignInRouteTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val device = DeviceIdentity("dev-1", "Pixel", "Shoalwatch", "0.1")
    private val gateway = FakeGateway()
    private lateinit var db: AppDatabase
    private lateinit var store: ServerStore
    private lateinit var repo: AuthRepository

    private val home = ServerAddress.parse("http://192.168.1.5:8096")
    private val away = ServerAddress.parse("https://away.example")
    private val server = Server(S1, "Den", "10.10.3", home, away, true, null, null, emptyMap())

    /** On the home Wi-Fi the selector picks the (http) home address. */
    private val connections = StubConnections { Connection.Online(home, isHome = true) }

    @Before fun open() {
        db = inMemoryDb()
        store = ServerStore(db, SecretBox { key })
        repo = AuthRepository(gateway, store, device, connections)
        kotlinx.coroutines.runBlocking { store.upsertServer(server) }
    }

    @After fun close() = db.close()

    private fun awayUnreachable() {
        gateway.info = { if (it == away) throw AppError.of(AppError.Kind.NETWORK) else ServerInfo(S1, "Den", "10.10.3", it, ServerInfo.Compatibility.SUPPORTED) }
    }

    @Test fun whenBothAnswerThePasswordGoesToHttpsEvenThoughTheSelectorChoseHttp() = runTest {
        val route = repo.signInRoute(S1)!!
        assertTrue(route.encrypted)
        repo.signInWithPassword(route, "ben", "pw".toCharArray())
        assertEquals(listOf("authName" to away), gateway.log.filter { it.first == "authName" })
    }

    @Test fun whenBothAnswerQuickConnectGoesToHttpsToo() = runTest {
        val states = repo.quickConnect(repo.signInRoute(S1)!!).toList()
        assertTrue(states.last() is QuickConnectState.Success)
        assertTrue(gateway.log.filter { it.first != "info" }.all { it.second == away })
    }

    @Test fun whenHttpsDoesNotAnswerTheRouteIsHttpAndSaysSo() = runTest {
        awayUnreachable()
        val route = repo.signInRoute(S1)!!
        assertFalse(route.encrypted)
        repo.signInWithPassword(route, "ben", "pw".toCharArray())
        assertEquals(listOf("authName" to home), gateway.log.filter { it.first == "authName" })
    }

    @Test fun aServerWithOnlyAnHttpAddressGetsAnUnencryptedRoute() = runTest {
        store.upsertServer(server.copy(awayAddress = null))
        assertFalse(repo.signInRoute(S1)!!.encrypted)
        assertEquals("nothing else was probed", listOf<String>(), gateway.calls)
    }

    @Test fun anHttpsAddressChosenByTheSelectorIsUsedAsIs() = runTest {
        connections.online = { Connection.Online(away, isHome = false) }
        assertTrue(repo.signInRoute(S1)!!.encrypted)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test fun nothingVerifiedMeansNoRoute() = runTest {
        connections.online = { null }
        assertNull(repo.signInRoute(S1))
        assertTrue(gateway.calls.isEmpty())
    }

    @Test fun aSelectorAddressThatIsNoLongerSavedIsNotUsed() = runTest {
        connections.online = { Connection.Online(ServerAddress.parse("http://10.0.0.9:8096"), isHome = true) }
        assertNull(repo.signInRoute(S1))
    }
}
