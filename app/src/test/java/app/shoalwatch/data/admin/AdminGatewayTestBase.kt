package app.shoalwatch.data.admin

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.core.AppError
import app.shoalwatch.data.admin.api.AdminGateway
import app.shoalwatch.data.api.ServerApi
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.inMemoryDb
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerClients
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.data.servers.Session
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
import org.junit.Assert.fail
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator

/** The real [AdminGateway] on the real [ServerApi] and OkHttp, against MockWebServer and a real in-memory store. */
@RunWith(RobolectricTestRunner::class)
abstract class AdminGatewayTestBase {
    private class OnlineSelector(val online: Connection.Online) : ServerConnections {
        override fun connection(serverId: String): StateFlow<Connection> = MutableStateFlow(online)
        override suspend fun awaitOnline(serverId: String, timeoutMs: Long): Connection.Online = online
        override fun refresh() {}
        override fun refresh(serverId: String) {}
        override fun refreshIfCurrent(serverId: String, failed: Connection.Online) {}
    }

    protected val web = MockWebServer()
    protected val session = Session(S1, U1)
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val clients = ServerClients(HttpClientFactory(HandshakeCertificates.Builder().build().trustManager, { error("no client certificates here") }))
    private lateinit var db: AppDatabase
    protected lateinit var store: ServerStore
    internal lateinit var gateway: AdminGateway

    /** What `AdminRights.allows` answers; true unless a test says otherwise. */
    protected var allowed = true

    @Before fun open() {
        web.start()
        db = inMemoryDb()
        store = ServerStore(db, SecretBox { key }, clients::evict)
        val address = ServerAddress.parse("http://127.0.0.1:${web.port}")
        val api = ServerApi(store, OnlineSelector(Connection.Online(address, isHome = true)), clients, DeviceIdentity("dev-1", "Pixel", "Shoalwatch", "0.1"))
        gateway = AdminGateway(api, AdminFixtures.THIS_PHONE) { s -> allowed && s == session }
        runBlocking {
            store.upsertServer(Server(S1, "Den", "12.4.0", address, null, true, null, null, emptyMap()))
            store.upsertAccount(Account(S1, U1, "ben", TOKEN), makeActive = true)
            store.warmTokens()
        }
    }

    @After fun close() { db.close(); web.shutdown() }

    protected fun nextRequest(): RecordedRequest = web.takeRequest(3, TimeUnit.SECONDS) ?: error("no request was sent")

    protected fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    protected fun fixture(name: String) = json(AdminFixtures.text(name))

    protected fun noContent() = MockResponse().setResponseCode(204)

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
        const val TOKEN = "tok-1"
        const val LIBRARY = "1a9f2c3e4d5a6b7c8d9e0f1a2b3c4d5e"
        const val TASK = "8a6a3f1b2c4d5e6f7a8b9c0d1e2f3a4b"
        const val SESSION_ID = "a1b2c3d4e5f60718293a4b5c6d7e8f90"
    }
}
