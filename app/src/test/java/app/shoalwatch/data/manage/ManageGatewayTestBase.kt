package app.shoalwatch.data.manage

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.core.AppError
import app.shoalwatch.data.api.ServerApi
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.inMemoryDb
import app.shoalwatch.data.manage.api.ManageGateway
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

/** The real [ManageGateway] on the real [ServerApi] and OkHttp, against MockWebServer and a real in-memory store. */
@RunWith(RobolectricTestRunner::class)
abstract class ManageGatewayTestBase {
    private inner class OnlineSelector(val online: Connection.Online) : ServerConnections {
        override fun connection(serverId: String): StateFlow<Connection> = MutableStateFlow(online)
        override suspend fun awaitOnline(serverId: String, timeoutMs: Long): Connection.Online = online.also { whileWaitingForTheAddress() }
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
    internal lateinit var gateway: ManageGateway

    /** What `AdminRights.allows` answers; true unless a test says otherwise. */
    protected var allowed = true
    /** What `AdminRights.allowsDelete` answers. */
    protected var mayDelete = true

    /** Runs inside every `awaitOnline`: what happens while a call waits for the address. */
    protected var whileWaitingForTheAddress: () -> Unit = {}

    @Before fun open() {
        web.start()
        db = inMemoryDb()
        store = ServerStore(db, SecretBox { key }, clients::evict)
        val address = ServerAddress.parse("http://127.0.0.1:${web.port}")
        val api = ServerApi(store, OnlineSelector(Connection.Online(address, isHome = true)), clients, DeviceIdentity("dev-1", "Pixel", "Shoalwatch", "0.1"))
        gateway = ManageGateway(api, { s -> allowed && s == session }, { s -> mayDelete && s == session })
        runBlocking {
            store.upsertServer(Server(S1, "Den", "12.4.0", address, null, true, null, null, emptyMap()))
            store.upsertAccount(Account(S1, U1, "ben", "tok-1"), makeActive = true)
            store.warmTokens()
        }
    }

    @After fun close() { db.close(); web.shutdown() }

    protected fun nextRequest(): RecordedRequest = web.takeRequest(3, TimeUnit.SECONDS) ?: error("no request was sent")

    protected fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

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
        const val ITEM = "1a9f2c3e4d5a6b7c8d9e0f1a2b3c4d5e"
        const val DASHED = "1a9f2c3e-4d5a-6b7c-8d9e-0f1a2b3c4d5e"
    }
}
