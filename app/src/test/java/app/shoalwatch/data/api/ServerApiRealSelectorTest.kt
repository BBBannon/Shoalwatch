package app.shoalwatch.data.api

import app.shoalwatch.S1
import kotlinx.coroutines.flow.flowOf
import app.shoalwatch.U1
import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.inMemoryDb
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.AddressSelector
import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.JellyfinGateway
import app.shoalwatch.data.servers.JellyfinGatewayImpl
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerClients
import app.shoalwatch.data.servers.ServerInfo
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.data.servers.Session
import app.shoalwatch.net.HttpClientFactory
import app.shoalwatch.net.NetworkMonitor
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.net.ServerNetConfig
import app.shoalwatch.security.SecretBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator

/** `ServerApi` on the REAL `AddressSelector` and OkHttp, against MockWebServers: what the address selector and the call path do together. */
@RunWith(RobolectricTestRunner::class)
class ServerApiRealSelectorTest {
    private class CountingGateway(private val inner: JellyfinGateway) : JellyfinGateway by inner {
        val probes = AtomicInteger()
        override suspend fun publicInfo(address: ServerAddress, net: ServerNetConfig): ServerInfo {
            probes.incrementAndGet()
            return inner.publicInfo(address, net)
        }
    }

    /** Answers the identity probe as [S1] and an API route with `[]`; records what it receives. */
    private class Jellyfin : Dispatcher() {
        val received = CopyOnWriteArrayList<RecordedRequest>()
        override fun dispatch(request: RecordedRequest): MockResponse {
            received += request
            return when (request.path) {
                "/System/Info/Public" -> MockResponse().setHeader("Content-Type", "application/json").setBody("""{"Id":"$S1","ServerName":"Den","Version":"10.10.3"}""")
                else -> MockResponse().setHeader("Content-Type", "application/json").setBody("[]")
            }
        }
    }

    private val home = MockWebServer()
    private val away = MockWebServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val device = DeviceIdentity("dev-1", "Pixel", "Shoalwatch", "0.1")
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val clients = ServerClients(HttpClientFactory(HandshakeCertificates.Builder().build().trustManager, { error("no client certificates here") }))
    private val session = Session(S1, U1)
    private lateinit var db: AppDatabase
    private lateinit var gateway: CountingGateway
    private lateinit var selector: AddressSelector
    private lateinit var api: ServerApi

    @Before fun open() {
        db = inMemoryDb()
    }

    @After fun close() {
        scope.cancel()
        db.close()
        runCatching { home.shutdown() }
        runCatching { away.shutdown() }
    }

    private fun url(web: MockWebServer) = ServerAddress.parse("http://127.0.0.1:${web.port}")

    private fun wire(homeAddress: ServerAddress?, awayAddress: ServerAddress?) {
        val store = ServerStore(db, SecretBox { key }, clients::evict)
        gateway = CountingGateway(JellyfinGatewayImpl(clients))
        selector = AddressSelector(gateway, object : NetworkMonitor { override val changes = emptyFlow<Unit>() }, scope, device, store, flowOf(true))
        api = ServerApi(store, selector, clients, device)
        runBlocking {
            store.upsertServer(Server(S1, "Den", "10.10.3", homeAddress, awayAddress, true, null, null, emptyMap()))
            store.upsertAccount(Account(S1, U1, "ben", "tok-1"), makeActive = true)
            store.warmTokens()
        }
    }

    private fun get() = runBlocking { api.get(session, "/Things", emptyList(), ListSerializer(String.serializer())) }

    @Test fun withHomeDeadAndAwayAliveTheRequestLandsOnAway() {
        val dead = MockWebServer().apply { start() }
        val deadAddress = url(dead)
        dead.shutdown()
        val server = Jellyfin()
        away.dispatcher = server
        away.start()
        wire(deadAddress, url(away))
        assertEquals(emptyList<String>(), get())
        val things = server.received.single { it.path == "/Things" }
        assertTrue(things.getHeader("Authorization")!!.contains("Token=\"tok-1\""))
        assertEquals("the identity probe carried no token", null, server.received.first { it.path == "/System/Info/Public" }.getHeader("Authorization")?.takeIf { "Token=" in it })
    }

    @Test fun manyParallelFailuresOnADeadAddressCauseOneProbeCycleNotOnePerRequest() {
        val server = Jellyfin()
        home.dispatcher = server
        home.start()
        wire(url(home), null)
        runBlocking { selector.awaitOnline(S1) }
        assertEquals("the initial verification", 1, gateway.probes.get())
        home.shutdown() // the network moved: the verified address is now dead

        val results = runBlocking {
            (1..8).map { async(Dispatchers.Default) { runCatching { api.get(session, "/Things", emptyList(), ListSerializer(String.serializer())) } } }.awaitAll()
        }
        assertTrue("every request failed cleanly", results.all { r -> (r.exceptionOrNull() as? AppError)?.kind in setOf(AppError.Kind.OFFLINE, AppError.Kind.NETWORK) })
        assertEquals("one re-check in total, not one per failed request", 2, gateway.probes.get())
    }
}
