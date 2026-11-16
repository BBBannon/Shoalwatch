package app.shoalwatch.data

import app.shoalwatch.data.db.AppDatabase
import kotlinx.coroutines.flow.flowOf
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.AddressSelector
import app.shoalwatch.data.servers.AuthRepository
import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.JellyfinGatewayImpl
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerClients
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.data.servers.StoredToken
import app.shoalwatch.net.HttpClientFactory
import app.shoalwatch.net.NetworkMonitor
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.security.SecretBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.KeyGenerator

/**
 * Review I3 end to end, through real OkHttp, the real address selector and two MockWebServers: a plain-http home
 * address and an https away address. Assertions are on the requests each server actually received.
 */
@RunWith(RobolectricTestRunner::class)
class RevocationOverNetworkTest {
    private val serverId = "0123abcd456789ef0123456789abcdef"
    private val impostorId = "ffffffffffffffffffffffffffffffff"

    private val root = HeldCertificate.Builder().certificateAuthority(0).build()
    private val leaf = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").signedBy(root).build()

    /** Answers `/System/Info/Public` as [id] and accepts a logout; records everything it receives. */
    private class Jellyfin(private val id: String) : Dispatcher() {
        val received = CopyOnWriteArrayList<RecordedRequest>()
        override fun dispatch(request: RecordedRequest): MockResponse {
            received += request
            return when (request.path) {
                "/System/Info/Public" -> MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"Id":"$id","ServerName":"Den","Version":"10.10.3"}""")
                "/Sessions/Logout" -> MockResponse().setResponseCode(204)
                else -> MockResponse().setResponseCode(404)
            }
        }
        fun tokenBearing() = received.filter { it.getHeader("Authorization")?.contains("Token=") == true }
    }

    private val homeWeb = MockWebServer()
    private val awayWeb = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var db: AppDatabase

    @After fun stop() {
        scope.cancel()
        if (::db.isInitialized) db.close()
        homeWeb.shutdown()
        awayWeb.shutdown()
    }

    /** Wires the real stack as AppGraph does, with a server whose home is http and away is https. */
    private fun repo(home: Jellyfin, away: Jellyfin?): Pair<AuthRepository, ServerStore> {
        homeWeb.dispatcher = home
        homeWeb.start()
        if (away != null) {
            awayWeb.useHttps(HandshakeCertificates.Builder().heldCertificate(leaf, root.certificate).build().sslSocketFactory(), false)
            awayWeb.dispatcher = away
            awayWeb.start()
        }
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build().trustManager
        val clients = ServerClients(HttpClientFactory(trust, { error("no client certificates here") }))
        val gateway = JellyfinGatewayImpl(clients)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        db = inMemoryDb()
        val device = DeviceIdentity("dev-1", "Pixel", "Shoalwatch", "0.1")
        lateinit var selector: AddressSelector
        val store = ServerStore(db, SecretBox { key }, clients::evict, onServerRemoved = { selector.forget(it) })
        val monitor = object : NetworkMonitor { override val changes = emptyFlow<Unit>() }
        selector = AddressSelector(gateway, monitor, scope, device, store, flowOf(true))
        val awayAddress = if (away != null) ServerAddress.parse("https://127.0.0.1:${awayWeb.port}") else ServerAddress.parse("https://127.0.0.1:1")
        runBlocking {
            store.upsertServer(Server(serverId, "Den", "10.10.3", ServerAddress.parse("http://127.0.0.1:${homeWeb.port}"), awayAddress, true, null, null, emptyMap()))
            store.upsertAccount(Account(serverId, "fedcba9876543210fedcba9876543210", "ben", "tok-1"), makeActive = true)
        }
        return AuthRepository(gateway, store, device, selector) to store
    }

    private val userId = "fedcba9876543210fedcba9876543210"

    @Test fun anImpostorOnTheHomeAddressReceivesNoTokenAndTheRealServerRevokesIt() {
        val home = Jellyfin(impostorId)
        val away = Jellyfin(serverId)
        val (repo, store) = repo(home, away)
        assertTrue(runBlocking { repo.signOut(serverId, userId) })
        assertEquals("the impostor got no token-bearing request", 0, home.tokenBearing().size)
        assertTrue(home.received.none { it.path == "/Sessions/Logout" })
        assertEquals(listOf("/Sessions/Logout"), away.tokenBearing().map { it.path })
        assertTrue(away.tokenBearing().single().getHeader("Authorization")!!.contains("Token=\"tok-1\""))
        assertNull(store.cachedToken(serverId, userId))
    }

    @Test fun whenBothAnswerTheTokenGoesOverHttpsNotHttp() {
        val home = Jellyfin(serverId)
        val away = Jellyfin(serverId)
        val (repo, _) = repo(home, away)
        assertTrue(runBlocking { repo.signOut(serverId, userId) })
        assertEquals(0, home.tokenBearing().size)
        assertEquals(1, away.tokenBearing().size)
    }

    @Test fun withOnlyAnImpostorReachableTheTokenGoesNowhereAndTheUserIsTold() {
        val home = Jellyfin(impostorId)
        val (repo, store) = repo(home, away = null)
        assertFalse(runBlocking { repo.signOut(serverId, userId) })
        assertEquals(0, home.tokenBearing().size)
        assertEquals(StoredToken.Missing, runBlocking { store.token(serverId, userId) })
    }

    @Test fun removingTheServerRevokesThroughTheRealServerOnlyThenDeletes() {
        val home = Jellyfin(impostorId)
        val away = Jellyfin(serverId)
        val (repo, store) = repo(home, away)
        assertTrue(runBlocking { repo.removeServer(serverId) })
        assertEquals(0, home.tokenBearing().size)
        assertEquals(1, away.tokenBearing().size)
        assertNull(runBlocking { store.server(serverId) })
    }
}
