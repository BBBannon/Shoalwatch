package app.shoalwatch.data

import app.shoalwatch.testId
import kotlinx.coroutines.flow.flowOf
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.AddressSelector
import app.shoalwatch.data.servers.AuthRepository
import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.JellyfinGatewayImpl
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerClients
import app.shoalwatch.data.servers.ServerNet
import app.shoalwatch.data.servers.ServerStore
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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.KeyGenerator

/**
 * Revocation sends the token being revoked through its own one-off client, never through the client cached for the
 * account's live key (which holds another token provider), and leaves no client for it behind (review F3).
 */
@RunWith(RobolectricTestRunner::class)
class RevocationClientCacheTest {
    private val serverId = testId("srv")
    private val userId = testId("usr")
    private val tokenLive = "live-token-A"
    private val tokenRevoked = "revoked-token-B"

    private val received = CopyOnWriteArrayList<RecordedRequest>()
    private val web = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                received += request
                return when (request.path) {
                    "/System/Info/Public" -> MockResponse().setHeader("Content-Type", "application/json")
                        .setBody("""{"Id":"$serverId","ServerName":"Den","Version":"10.10.3"}""")
                    "/Sessions/Logout" -> MockResponse().setResponseCode(204)
                    else -> MockResponse().setResponseCode(200).setBody("{}")
                }
            }
        }
        start()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val trust = HandshakeCertificates.Builder().build().trustManager
    private val clients = ServerClients(HttpClientFactory(trust, { error("no client certificates here") }))
    private val device = DeviceIdentity("dev-1", "Pixel", "Shoalwatch", "0.1")
    private val address = ServerAddress.parse("http://127.0.0.1:${web.port}")
    private val server = Server(serverId, "Den", "10.10.3", address, null, true, null, null, emptyMap())
    private val db = inMemoryDb()

    @After fun stop() {
        scope.cancel()
        db.close()
        web.shutdown()
    }

    private fun repo(): AuthRepository {
        val gateway = JellyfinGatewayImpl(clients)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        lateinit var selector: AddressSelector
        val store = ServerStore(db, SecretBox { key }, clients::evict, onServerRemoved = { selector.forget(it) })
        selector = AddressSelector(gateway, object : NetworkMonitor { override val changes = emptyFlow<Unit>() }, scope, device, store, flowOf(true))
        runBlocking {
            store.upsertServer(server)
            // The account's stored token is the one to revoke.
            store.upsertAccount(Account(serverId, userId, "ben", tokenRevoked), makeActive = true)
        }
        return AuthRepository(gateway, store, device, selector)
    }

    private fun get(client: OkHttpClient) =
        client.newCall(Request.Builder().url(address.value + "/Items").build()).execute().close()

    private fun authOf(path: String) = received.last { it.path == path }.getHeader("Authorization")!!

    @Test fun revokingSendsTheRevokedTokenNotTheLiveClientsAndLeavesTheCachedClientAlone() {
        val repo = repo()
        // A long-lived authenticated client for the same account key, as Milestone 2 will hold, with token A.
        val live = ServerNet.authenticated(listOf(address), server, userId, device) { _, _ -> tokenLive }
        val cached = clients.clientFor(live)
        get(cached)
        assertTrue(authOf("/Items").contains("Token=\"$tokenLive\""))

        assertTrue(runBlocking { repo.signOut(serverId, userId) })

        val logout = authOf("/Sessions/Logout")
        assertTrue("logout carries the revoked token", logout.contains("Token=\"$tokenRevoked\""))
        assertTrue("logout does not carry the live client's token", !logout.contains(tokenLive))
    }

    @Test fun theCachedClientForTheAccountIsUntouchedByRevocation() {
        val live = ServerNet.authenticated(listOf(address), server, userId, device) { _, _ -> tokenLive }
        val cached = clients.clientFor(live)
        // Revoke without removing the account (removal would evict the live client by design): go through the gateway.
        val revoked = ServerNet.revocation(listOf(address), server, userId, device, tokenRevoked)
        runBlocking { JellyfinGatewayImpl(clients).logout(address, revoked) }
        assertTrue(authOf("/Sessions/Logout").contains("Token=\"$tokenRevoked\""))

        assertSame("same cached client", cached, clients.clientFor(live))
        assertEquals("only the live client is cached: nothing holds the revoked token", 1, clients.cachedCount())
        get(clients.clientFor(live))
        assertTrue("the live client still sends its own token", authOf("/Items").contains("Token=\"$tokenLive\""))
    }

    @Test fun theSharedCacheRefusesARevocationConfigurationSoACapturedTokenClientCanNeverBeCached() {
        val revoked = ServerNet.revocation(listOf(address), server, userId, device, tokenRevoked)
        val e = try { clients.clientFor(revoked); null } catch (e: IllegalArgumentException) { e }
        assertTrue("refused", e != null)
        assertTrue("the token is not echoed", !e!!.message!!.contains(tokenRevoked))
        assertEquals(0, clients.cachedCount())
    }
}
