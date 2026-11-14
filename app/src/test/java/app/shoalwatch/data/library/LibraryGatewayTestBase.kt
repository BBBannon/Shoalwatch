package app.shoalwatch.data.library

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.core.AppError
import app.shoalwatch.data.api.ServerApi
import app.shoalwatch.data.api.Session
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.inMemoryDb
import app.shoalwatch.data.library.api.LibraryGateway
import app.shoalwatch.data.library.api.LibraryGatewayImpl
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerClients
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.net.HttpClientFactory
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.security.SecretBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

/** The real [LibraryGatewayImpl] on the real [ServerApi] and OkHttp, against MockWebServer, a real in-memory store and a selector that is online (or not). */
@RunWith(RobolectricTestRunner::class)
abstract class LibraryGatewayTestBase {
    private class FakeSelector(var online: Connection.Online?) : ServerConnections {
        override fun connection(serverId: String): StateFlow<Connection> = MutableStateFlow(Connection.Offline())
        override suspend fun awaitOnline(serverId: String, timeoutMs: Long): Connection.Online? = online
        override fun refresh() {}
    }

    protected val web = MockWebServer()
    protected val session = Session(S1, U1)
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val clients = ServerClients(HttpClientFactory(HandshakeCertificates.Builder().build().trustManager, { error("no client certificates here") }))
    private lateinit var db: AppDatabase
    private lateinit var selector: FakeSelector
    protected lateinit var gateway: LibraryGateway

    @Before fun open() {
        web.start()
        db = inMemoryDb()
        val store = ServerStore(db, SecretBox { key }, clients::evict)
        val address = ServerAddress.parse("http://127.0.0.1:${web.port}")
        selector = FakeSelector(Connection.Online(address, isHome = true))
        gateway = LibraryGatewayImpl(ServerApi(store, selector, clients, DeviceIdentity("dev-1", "Pixel", "Shoalwatch", "0.1")))
        runBlocking {
            store.upsertServer(Server(S1, "Den", "12.4.0", address, null, true, null, null, emptyMap()))
            store.upsertAccount(Account(S1, U1, "ben", "tok-1"), makeActive = true)
            store.warmTokens()
        }
    }

    @After fun close() { db.close(); web.shutdown() }

    /** The next request the server received; fails (rather than hanging) when there was none. */
    protected fun nextRequest(): RecordedRequest = web.takeRequest(3, TimeUnit.SECONDS) ?: error("no request was sent")

    protected fun goOffline() { selector.online = null }

    protected fun fixture(name: String): String = javaClass.getResource("/jellyfin/$name")!!.readText()

    protected fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    protected fun enqueueFixture(name: String) = web.enqueue(json(fixture(name)))

    /** Asserts the method, the path and EXACTLY these query parameters (names are case-sensitive), and returns the request. */
    protected fun assertRequest(method: String, path: String, query: Map<String, String>): RecordedRequest {
        val request = nextRequest()
        assertEquals(method, request.method)
        val url = request.requestUrl!!
        assertEquals(path, url.encodedPath)
        assertEquals(query.keys, url.queryParameterNames)
        query.forEach { (name, value) -> assertEquals("parameter $name", value, url.queryParameter(name)) }
        return request
    }

    protected fun failure(block: suspend () -> Unit): AppError {
        try {
            runBlocking { block() }
        } catch (e: AppError) {
            return e
        }
        fail("expected an AppError")
        error("unreachable")
    }

    protected companion object {
        val IMAGE_QUERY = mapOf("enableUserData" to "true", "enableImages" to "true", "enableImageTypes" to "Primary,Backdrop,Thumb", "imageTypeLimit" to "1")
        const val FILMS = "c0ffee00000000000000000000000001"
        const val TV = "c0ffee00000000000000000000000002"
        const val MOVIE_A = "aa000000000000000000000000000001"
        const val MOVIE_B = "aa000000000000000000000000000002"
        const val MOVIE_C = "aa000000000000000000000000000003"
        const val SERIES = "aa000000000000000000000000000004"
        const val BOX_SET = "aa000000000000000000000000000005"
        const val SEASON_1 = "5e0000000000000000000000000000a1"
        const val SEASON_2 = "5e0000000000000000000000000000a2"
        const val EPISODE_11 = "ee00000000000000000000000000e101"
        const val EPISODE_12 = "ee00000000000000000000000000e102"
        const val EPISODE_21 = "ee00000000000000000000000000e201"
    }
}
