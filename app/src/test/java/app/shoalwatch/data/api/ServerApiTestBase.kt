package app.shoalwatch.data.api

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.inMemoryDb
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerClients
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.data.servers.ServerNet
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.data.servers.Session
import app.shoalwatch.net.HttpClientFactory
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.security.SecretBox
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.KeyGenerator

/** The one authenticated call path, through real OkHttp (real `HttpClientFactory`/`ServerClients`) against MockWebServer and a real in-memory store. */
@RunWith(RobolectricTestRunner::class)
abstract class ServerApiTestBase {
    @Serializable protected class Thing(@SerialName("Name") val name: String? = null, @SerialName("Count") val count: Int = 0)

    /** Answers `awaitOnline` from [answer] (given the 1-based ask number) and counts what it is asked. */
    protected class FakeSelector(var answer: suspend (Int) -> Connection.Online?) : ServerConnections {
        val asks = AtomicInteger()
        /** Calls to `refreshIfCurrent` (what `ServerApi` uses after a failure) and to the global `refresh` (which it must never use). */
        val refreshes = AtomicInteger()
        val globalRefreshes = AtomicInteger()
        val lastFailed = AtomicReference<Connection.Online?>()
        /** The live state: the last address [awaitOnline] verified (a test may set it, e.g. to `Checking`, to model a network change). */
        val state = MutableStateFlow<Connection>(Connection.Offline())
        /** Runs inside every [connection] call: lets a test change something at that exact moment of `ServerApi.mediaRoute`. */
        @Volatile var onConnection: () -> Unit = {}
        override fun connection(serverId: String): StateFlow<Connection> = state.also { onConnection() }
        override suspend fun awaitOnline(serverId: String, timeoutMs: Long): Connection.Online? =
            answer(asks.incrementAndGet()).also { if (it != null) state.value = it }
        override fun refresh() { globalRefreshes.incrementAndGet() }
        override fun refresh(serverId: String) = Unit
        override fun refreshIfCurrent(serverId: String, failed: Connection.Online) { refreshes.incrementAndGet(); lastFailed.set(failed) }
    }

    protected val web = MockWebServer()
    protected val device = DeviceIdentity("dev-1", "Pixel", "Shoalwatch", "0.1")
    protected val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    protected val clients = ServerClients(HttpClientFactory(HandshakeCertificates.Builder().build().trustManager, { error("no client certificates here") }))
    protected val session = Session(S1, U1)
    protected lateinit var db: AppDatabase
    protected lateinit var store: ServerStore
    protected lateinit var selector: FakeSelector
    protected lateinit var api: ServerApi

    @Before fun open() {
        web.start()
        db = inMemoryDb()
        store = ServerStore(db, SecretBox { key }, clients::evict)
        selector = FakeSelector { online() }
        api = ServerApi(store, selector, clients, device)
        saveServer()
        runBlocking {
            store.upsertAccount(Account(S1, U1, "ben", "tok-1"), makeActive = true)
            store.warmTokens()
        }
    }

    @After fun close() { db.close(); web.shutdown() }

    protected fun address(path: String = "") = ServerAddress.parse("http://127.0.0.1:${web.port}$path")
    protected fun online(path: String = "") = Connection.Online(address(path), isHome = true)

    protected fun saveServer(path: String = "", cleartext: Boolean = true, away: ServerAddress? = null) =
        runBlocking { store.upsertServer(Server(S1, "Den", "10.10.3", address(path), away, cleartext, null, null, emptyMap())) }

    protected fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    protected fun things() = ListSerializer(Thing.serializer())

    protected fun getThings(path: String = "/Things", query: List<Pair<String, String>> = emptyList(), retry: Boolean = true) =
        runBlocking { api.get(session, path, query, things(), retry = retry) }

    protected fun failure(block: () -> Unit): AppError {
        try {
            block()
        } catch (e: AppError) {
            return e
        }
        fail("expected an AppError")
        error("unreachable")
    }

    /** The live client for the account, as the API itself obtains it (equal configurations share one). */
    protected fun liveClient() = clients.clientFor(ServerNet.authenticated(listOf(address()), runBlocking { store.server(S1)!! }, U1, device, store::cachedToken))

    /** Makes every request the server receives fail at the socket while [failing] is set. */
    protected fun failWhile(failing: AtomicBoolean, ok: MockResponse = json("""[{"Name":"a","Count":1}]""")) {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                if (failing.get()) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else ok
        }
    }
}
