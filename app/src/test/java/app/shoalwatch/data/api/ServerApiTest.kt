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
import javax.crypto.KeyGenerator

/** The one authenticated call path, through real OkHttp (real `HttpClientFactory`/`ServerClients`) against MockWebServer and a real in-memory store. */
@RunWith(RobolectricTestRunner::class)
class ServerApiTest {
    @Serializable private class Thing(@SerialName("Name") val name: String? = null, @SerialName("Count") val count: Int = 0)

    /** Answers `awaitOnline` from [answer] (given the 1-based ask number) and counts what it is asked. */
    private class FakeSelector(var answer: suspend (Int) -> Connection.Online?) : ServerConnections {
        val asks = AtomicInteger()
        val refreshes = AtomicInteger()
        override fun connection(serverId: String): StateFlow<Connection> = MutableStateFlow(Connection.Offline())
        override suspend fun awaitOnline(serverId: String, timeoutMs: Long): Connection.Online? = answer(asks.incrementAndGet())
        override fun refresh() { refreshes.incrementAndGet() }
    }

    private val web = MockWebServer()
    private val device = DeviceIdentity("dev-1", "Pixel", "Shoalwatch", "0.1")
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val clients = ServerClients(HttpClientFactory(HandshakeCertificates.Builder().build().trustManager, { error("no client certificates here") }))
    private val session = Session(S1, U1)
    private lateinit var db: AppDatabase
    private lateinit var store: ServerStore
    private lateinit var selector: FakeSelector
    private lateinit var api: ServerApi

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

    private fun address(path: String = "") = ServerAddress.parse("http://127.0.0.1:${web.port}$path")
    private fun online(path: String = "") = Connection.Online(address(path), isHome = true)

    private fun saveServer(path: String = "", cleartext: Boolean = true) =
        runBlocking { store.upsertServer(Server(S1, "Den", "10.10.3", address(path), null, cleartext, null, null, emptyMap())) }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    private fun things() = ListSerializer(Thing.serializer())

    private fun getThings(path: String = "/Things", query: List<Pair<String, String>> = emptyList(), retry: Boolean = true) =
        runBlocking { api.get(session, path, query, things(), retry = retry) }

    private fun failure(block: () -> Unit): AppError {
        try {
            block()
        } catch (e: AppError) {
            return e
        }
        fail("expected an AppError")
        error("unreachable")
    }

    /** The live client for the account, as the API itself obtains it (equal configurations share one). */
    private fun liveClient() = clients.clientFor(ServerNet.authenticated(listOf(address()), runBlocking { store.server(S1)!! }, U1, device, store::cachedToken))

    /** Makes every request the server receives fail at the socket while [failing] is set. */
    private fun failWhile(failing: AtomicBoolean, ok: MockResponse = json("""[{"Name":"a","Count":1}]""")) {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) =
                if (failing.get()) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST) else ok
        }
    }

    // ---- the happy path ----

    @Test fun getDecodesJsonAndSendsTheCurrentTokenInTheAuthorizationHeaderOnly() {
        web.enqueue(json("""[{"Name":"Alien","Count":3,"Extra":true},{"Name":null}]"""))
        val result = getThings(query = listOf("limit" to "2"))
        assertEquals(listOf("Alien", null), result.map { it.name })
        assertEquals(3, result[0].count)
        val request = web.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/Things?limit=2", request.path)
        assertTrue(request.getHeader("Authorization")!!.contains("Token=\"tok-1\""))
        assertFalse(request.path!!.contains("tok-1"))
    }

    @Test fun aReSignInIsPickedUpByTheCachedClient() {
        web.enqueue(json("[]")); web.enqueue(json("[]"))
        getThings()
        runBlocking { store.upsertAccount(Account(S1, U1, "ben", "tok-2"), makeActive = true) }
        getThings()
        assertTrue(web.takeRequest().getHeader("Authorization")!!.contains("Token=\"tok-1\""))
        assertTrue(web.takeRequest().getHeader("Authorization")!!.contains("Token=\"tok-2\""))
    }

    @Test fun nothingIsSentUntilAnAddressIsVerified() {
        val gate = CompletableDeferred<Connection.Online?>()
        selector.answer = { gate.await() }
        web.enqueue(json("[]"))
        runBlocking {
            val call = async(Dispatchers.Default) { api.get(session, "/Things", emptyList(), things()) }
            while (selector.asks.get() == 0) delay(5)
            delay(100)
            assertEquals("no request, so no token, before awaitOnline answers", 0, web.requestCount)
            gate.complete(online())
            call.await()
        }
        assertEquals(1, web.requestCount)
    }

    @Test fun theBaseOfTheVerifiedAddressIsKeptAndEverythingIsEncodedByTheBuilder() {
        saveServer(path = "/jellyfin")
        selector.answer = { online("/jellyfin") }
        web.enqueue(json("[]"))
        getThings(path = "/Items/abc/Images", query = listOf("searchTerm" to "a b&c=é", "limit" to "5"))
        val request = web.takeRequest()
        assertEquals("/jellyfin/Items/abc/Images?searchTerm=a%20b%26c%3D%C3%A9&limit=5", request.path)
        assertEquals("a b&c=é", request.requestUrl!!.queryParameter("searchTerm"))
        assertEquals(1, request.requestUrl!!.queryParameterValues("searchTerm").size)
    }

    @Test fun postSendsTheJsonBodyAndDeleteHasNone() {
        repeat(3) { web.enqueue(MockResponse().setResponseCode(204)) }
        runBlocking {
            api.post(session, "/UserPlayedItems/x", listOf("datePlayed" to "now"), """{"Played":true}""")
            api.post(session, "/UserPlayedItems/y")
            api.delete(session, "/UserFavoriteItems/x")
        }
        val a = web.takeRequest(); val b = web.takeRequest(); val c = web.takeRequest()
        assertEquals("POST", a.method); assertEquals("/UserPlayedItems/x?datePlayed=now", a.path)
        assertEquals("""{"Played":true}""", a.body.readUtf8())
        assertTrue(a.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("POST", b.method); assertEquals(0L, b.bodySize)
        assertEquals("DELETE", c.method)
        listOf(a, b, c).forEach { assertTrue(it.getHeader("Authorization")!!.contains("Token=\"tok-1\"")) }
    }

    @Test fun callsRunOffTheCallersThread() {
        web.enqueue(MockResponse().setBody("x"))
        val caller = Thread.currentThread()
        val seen = runBlocking { api.getBytes(session, "/x", maxBytes = 10) { _, _ -> Thread.currentThread() } }
        assertNotEquals(caller, seen)
    }

    // ---- not signed in / offline / missing server ----

    @Test fun aSignedOutAccountFailsAsNotSignedInAndSendsNothing() {
        runBlocking { store.removeAccount(S1, U1) }
        assertEquals(AppError.Kind.NOT_SIGNED_IN, failure { getThings() }.kind)
        assertEquals(0, web.requestCount)
    }

    @Test fun anAccountThatWasNeverSignedInFailsAsNotSignedInAndSendsNothing() {
        val e = failure { runBlocking { api.get(Session(S1, U2), "/Things", emptyList(), things()) } }
        assertEquals(AppError.Kind.NOT_SIGNED_IN, e.kind)
        assertEquals(0, web.requestCount)
    }

    @Test fun nothingIsSentBeforeTheTokensAreWarmed() {
        val cold = ServerStore(db, SecretBox { key }, clients::evict)
        val coldApi = ServerApi(cold, selector, clients, device)
        runBlocking {
            val call = launch(Dispatchers.Default, start = CoroutineStart.DEFAULT) { runCatching { coldApi.get(session, "/Things", emptyList(), things()) } }
            delay(150)
            assertTrue("still waiting for the warm gate", call.isActive)
            assertEquals(0, selector.asks.get())
            call.cancelAndJoin()
        }
    }

    @Test fun offlineIsOfflineWithZeroRequests() {
        selector.answer = { null }
        val e = failure { getThings() }
        assertEquals(AppError.Kind.OFFLINE, e.kind)
        assertEquals("You're offline. Showing what's saved on this phone where possible.", e.userMessage)
        assertEquals(0, web.requestCount)
        assertEquals("offline is not an IO failure: no refresh, no retry", 0, selector.refreshes.get())
    }

    @Test fun aServerThatIsNoLongerSavedIsNotFoundAndSendsNothing() {
        runBlocking { store.removeServer(S1) }
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, failure { getThings() }.kind)
        assertEquals(0, web.requestCount)
        assertEquals(0, selector.asks.get())
    }

    @Test fun aVerifiedAddressThatIsNotTheServersOwnIsNeverSentTheToken() {
        val stranger = MockWebServer().apply { start() }
        try {
            selector.answer = { Connection.Online(ServerAddress.parse("http://127.0.0.1:${stranger.port}"), isHome = true) }
            failure { getThings() }
            assertEquals(0, stranger.requestCount)
            assertEquals(0, web.requestCount)
        } finally {
            stranger.shutdown()
        }
    }

    @Test fun aGuardRefusalIsNeverRetried() {
        saveServer(cleartext = false)
        val e = failure { getThings() }
        assertEquals(AppError.Kind.CLEARTEXT_BLOCKED, e.kind)
        assertEquals(1, selector.asks.get())
        assertEquals(0, selector.refreshes.get())
        assertEquals(0, web.requestCount)
    }

    // ---- HTTP status mapping ----

    private fun kindFor(code: Int, location: String? = null): AppError.Kind {
        web.enqueue(MockResponse().setResponseCode(code).apply { if (location != null) setHeader("Location", location) })
        return failure { getThings() }.kind
    }

    @Test fun statusCodesMapToKinds() {
        assertEquals(AppError.Kind.UNAUTHORISED, kindFor(401))
        assertEquals(AppError.Kind.FORBIDDEN, kindFor(403))
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, kindFor(404))
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, kindFor(301))
        assertEquals(AppError.Kind.SERVER_ERROR, kindFor(500))
        assertEquals(AppError.Kind.SERVER_ERROR, kindFor(503))
        assertEquals(AppError.Kind.SERVER_ERROR, kindFor(400))
        assertEquals(AppError.Kind.SERVER_ERROR, kindFor(418))
        assertEquals("a response is an answer: never retried", 0, selector.refreshes.get())
        assertEquals(8, web.requestCount)
    }

    @Test fun aRedirectIsNotFollowedAndNothingReachesItsTarget() {
        val target = MockWebServer().apply { start() }
        try {
            target.enqueue(json("[]"))
            assertEquals(AppError.Kind.SERVER_NOT_FOUND, kindFor(307, target.url("/Things").toString()))
            assertEquals(0, target.requestCount)
            assertEquals(1, web.requestCount)
        } finally {
            target.shutdown()
        }
    }

    @Test fun statusErrorsOnPostAreMappedToo() {
        web.enqueue(MockResponse().setResponseCode(403))
        assertEquals(AppError.Kind.FORBIDDEN, failure { runBlocking { api.post(session, "/UserPlayedItems/x") } }.kind)
    }

    // ---- network failure: refresh, retry once ----

    @Test fun anIoFailureRefreshesAndRetriesOnceThroughAFreshlyVerifiedAddress() {
        val failing = AtomicBoolean(true)
        failWhile(failing)
        selector.answer = { n -> if (n >= 2) failing.set(false); online() }
        val result = getThings()
        assertEquals("a", result.single().name)
        assertEquals(1, selector.refreshes.get())
        assertEquals(2, selector.asks.get())
    }

    @Test fun aSecondIoFailureIsTheMappedErrorAfterExactlyOneRetry() {
        failWhile(AtomicBoolean(true))
        val e = failure { getThings() }
        assertEquals(AppError.Kind.NETWORK, e.kind)
        assertEquals(2, selector.asks.get())
        assertEquals(1, selector.refreshes.get())
    }

    @Test fun ifNothingIsVerifiedAfterTheFailureItIsOffline() {
        failWhile(AtomicBoolean(true))
        selector.answer = { n -> if (n == 1) online() else null }
        assertEquals(AppError.Kind.OFFLINE, failure { getThings() }.kind)
        assertEquals(2, selector.asks.get())
        assertEquals(1, selector.refreshes.get())
    }

    @Test fun getsCanOptOutOfTheRetry() {
        failWhile(AtomicBoolean(true))
        assertEquals(AppError.Kind.NETWORK, failure { getThings(retry = false) }.kind)
        assertEquals(1, selector.asks.get())
        assertEquals("the failure still prompts a re-check for the next call", 1, selector.refreshes.get())
    }

    @Test fun aPostIsNotRetriedByDefaultButCanOptIn() {
        val failing = AtomicBoolean(true)
        failWhile(failing, ok = MockResponse().setResponseCode(204))
        assertEquals(AppError.Kind.NETWORK, failure { runBlocking { api.post(session, "/UserPlayedItems/x") } }.kind)
        assertEquals(1, selector.asks.get())
        assertEquals(AppError.Kind.NETWORK, failure { runBlocking { api.delete(session, "/UserPlayedItems/x") } }.kind)
        assertEquals(2, selector.asks.get())

        selector.answer = { _ -> failing.set(false); online() }
        runBlocking { api.post(session, "/UserPlayedItems/x", retry = true) }
        assertEquals(3, selector.asks.get())
    }

    // ---- bodies ----

    @Test fun aBodyOverTheLimitIsRefusedAndOneAtTheLimitIsRead() {
        val body = """[{"Name":"${"x".repeat(100)}"}]"""
        web.enqueue(json(body)); web.enqueue(json(body))
        val e = failure { runBlocking { api.get(session, "/Things", emptyList(), things(), maxBytes = body.length - 1L) } }
        assertEquals(AppError.Kind.SERVER_ERROR, e.kind)
        assertEquals(100, runBlocking { api.get(session, "/Things", emptyList(), things(), maxBytes = body.length.toLong()) }.single().name!!.length)
    }

    @Test fun malformedJsonIsAFixedServerErrorThatNeverEchoesTheBody() {
        web.enqueue(json("""{"secret-looking":"tok-1" this is not json"""))
        val e = failure { getThings() }
        assertEquals(AppError.Kind.SERVER_ERROR, e.kind)
        assertEquals(AppError.of(AppError.Kind.SERVER_ERROR).userMessage, e.userMessage)
        assertFalse(e.toString().contains("secret-looking"))
        assertEquals("a bad body is an answer: never retried", 1, selector.asks.get())
    }

    // ---- path validation ----

    @Test fun badPathsAreProgrammerErrorsSentNowhere() {
        listOf(
            "Things", "/a/../b", "/..", "/a//b", "//x", "/a?x=1", "/a#f", "/a\\b", "/a\nb", "/a\u0000b", "/a/./b", "", "http://evil/x",
        ).forEach { path ->
            try {
                getThings(path = path)
                fail("accepted a bad path")
            } catch (e: IllegalArgumentException) {
                assertEquals("Invalid API path.", e.message)
            }
        }
        assertEquals(0, web.requestCount)
        assertEquals(0, selector.asks.get())
    }

    @Test fun pathsAreEncodedNotInterpreted() {
        web.enqueue(json("[]"))
        getThings(path = "/Items/a b%2Fc/Images")
        assertEquals("/Items/a%20b%252Fc/Images", web.takeRequest().path)
    }

    // ---- cancellation ----

    @Test fun cancellingTheCallerCancelsTheOkHttpCall() {
        web.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val client = liveClient()
        runBlocking {
            val call = launch(Dispatchers.Default) { api.get(session, "/Things", emptyList(), things()) }
            assertNotNull(web.takeRequest(5, TimeUnit.SECONDS))
            assertEquals(1, client.dispatcher.runningCallsCount())
            call.cancelAndJoin()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (client.dispatcher.runningCallsCount() > 0 && System.nanoTime() < deadline) delay(10)
            assertEquals("the call was cancelled, not left running", 0, client.dispatcher.runningCallsCount())
        }
    }

    @Test fun cancellingWhileTheBodyIsBeingReadReleasesTheCallPromptly() {
        web.enqueue(MockResponse().setBody("x".repeat(100)).throttleBody(1, 30, TimeUnit.SECONDS))
        runBlocking {
            val reading = CompletableDeferred<Unit>()
            val call = launch(Dispatchers.Default) { api.getBytes(session, "/x", maxBytes = 1000) { s, _ -> reading.complete(Unit); s.readByteArray() } }
            reading.await()
            val started = System.nanoTime()
            call.cancelAndJoin()
            assertTrue("cancelled well before the 30 s trickle ends", System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5))
            assertEquals("a cancellation is not a network failure", 0, selector.refreshes.get())
        }
    }

    // ---- getBytes ----

    @Test fun getBytesHandsOverTheBoundedSourceAndTheContentType() {
        web.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(byteArrayOf(1, 2, 3, 4))))
        val (bytes, type) = runBlocking { api.getBytes(session, "/Items/x/Images/Primary", listOf("maxWidth" to "300"), maxBytes = 4) { s, t -> s.readByteArray() to t } }
        assertEquals(listOf<Byte>(1, 2, 3, 4), bytes.toList())
        assertEquals("image/png", type)
        assertEquals("/Items/x/Images/Primary?maxWidth=300", web.takeRequest().path)
    }

    @Test fun getBytesRefusesToReadBeyondTheLimit() {
        web.enqueue(MockResponse().setHeader("Content-Type", "image/png").setChunkedBody(Buffer().write(ByteArray(64)), 8))
        val e = failure { runBlocking { api.getBytes(session, "/x", maxBytes = 16) { s, _ -> s.readByteArray() } } }
        assertEquals(AppError.Kind.SERVER_ERROR, e.kind)
    }

    @Test fun getBytesRefusesADeclaredSizeOverTheLimitWithoutRunningTheReader() {
        web.enqueue(MockResponse().setBody(Buffer().write(ByteArray(64))))
        var ran = false
        val e = failure { runBlocking { api.getBytes(session, "/x", maxBytes = 16) { _, _ -> ran = true } } }
        assertEquals(AppError.Kind.SERVER_ERROR, e.kind)
        assertFalse(ran)
    }

    @Test fun theResponseIsClosedWhenTheReaderThrows() {
        web.enqueue(MockResponse().setBody("abc")); web.enqueue(MockResponse().setBody("abc"))
        val client = liveClient()
        try {
            runBlocking { api.getBytes(session, "/x", maxBytes = 10) { _, _ -> throw IllegalStateException("reader failed") } }
            fail("expected the reader's exception")
        } catch (e: IllegalStateException) {
            assertEquals("reader failed", e.message)
        }
        runBlocking { api.getBytes(session, "/x", maxBytes = 10) { s, _ -> s.readUtf8() } }
        assertEquals("the first connection was released and reused, not leaked", 1, client.connectionPool.connectionCount())
    }

    // ---- misc ----

    @Test fun aSessionPrintsNothing() {
        assertFalse(session.toString().contains(S1))
        assertFalse(session.toString().contains(U1))
    }
}
