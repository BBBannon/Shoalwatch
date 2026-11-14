package app.offlinefin.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.offlinefin.data.db.AppDatabase
import app.offlinefin.data.servers.Account
import app.offlinefin.data.servers.AuthRepository
import app.offlinefin.data.servers.DeviceIdentity
import app.offlinefin.data.servers.JellyfinGatewayImpl
import app.offlinefin.data.servers.Server
import app.offlinefin.data.servers.ServerClients
import app.offlinefin.data.servers.ServerNet
import app.offlinefin.data.servers.ServerStore
import app.offlinefin.net.HttpClientFactory
import app.offlinefin.net.ServerAddress
import app.offlinefin.security.SecretBox
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.KeyGenerator

/** Signed-in clients read the token live: assertions are on the requests real OkHttp actually sends. */
@RunWith(RobolectricTestRunner::class)
class AccountTokenTest {
    private val web = MockWebServer()
    private val device = DeviceIdentity("dev-1", "Pixel", "OfflineFin", "0.1")
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val clients = ServerClients(HttpClientFactory(HandshakeCertificates.Builder().build().trustManager, { error("no client certificates here") }))
    private val gateway = JellyfinGatewayImpl(clients)
    private lateinit var db: AppDatabase
    private lateinit var store: ServerStore
    private lateinit var server: Server

    @Before fun open() {
        web.start()
        val address = ServerAddress.parse("http://127.0.0.1:${web.port}")
        server = Server("s1", "Den", "10.10.3", address, null, true, null, null, emptyMap())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        store = ServerStore(db, SecretBox { key }, clients::evict)
        runBlocking { store.upsertServer(server) }
    }

    @After fun close() { db.close(); web.shutdown() }

    private fun signIn(userId: String, token: String) = runBlocking { store.upsertAccount(Account("s1", userId, userId, token), makeActive = false) }

    /** Logs out through a freshly built configuration for [userId] and returns the Authorization header sent. */
    private fun sentHeader(userId: String): String {
        web.enqueue(MockResponse().setResponseCode(204))
        runBlocking { gateway.logout(server.homeAddress!!, ServerNet.authenticated(server.addresses, server, userId, device, store::cachedToken)) }
        return web.takeRequest().getHeader("Authorization")!!
    }

    @Test fun eachAccountOnTheSameServerSendsItsOwnToken() {
        signIn("a", "tok-A"); signIn("b", "tok-B")
        assertTrue("Token=\"tok-A\"" in sentHeader("a"))
        assertTrue("Token=\"tok-B\"" in sentHeader("b"))
        assertTrue("Token=\"tok-A\"" in sentHeader("a"))
    }

    @Test fun aSignedOutAccountNeverSendsItsTokenAgain() {
        signIn("a", "tok-A")
        assertTrue("Token=\"tok-A\"" in sentHeader("a"))
        val repo = AuthRepository(gateway, store, device)

        web.enqueue(MockResponse().setResponseCode(204))
        runBlocking { repo.signOut("s1", "a") }
        assertTrue("Token=\"tok-A\"" in web.takeRequest().getHeader("Authorization")!!)

        val after = sentHeader("a")
        assertFalse(after, "Token=" in after)
        assertFalse(after, "tok-A" in after)
    }

    @Test fun reSigningInIsPickedUpByTheCachedClient() {
        signIn("a", "tok-OLD")
        assertTrue("Token=\"tok-OLD\"" in sentHeader("a"))
        signIn("a", "tok-NEW")
        val header = sentHeader("a")
        assertTrue(header, "Token=\"tok-NEW\"" in header)
        assertFalse(header, "tok-OLD" in header)
    }

    @Test fun aColdStoreLoadsTheTokenWhenTheAccountIsRead() {
        signIn("a", "tok-A")
        // A new store over the same database = an app restart: nothing cached yet.
        store = ServerStore(db, SecretBox { key }, clients::evict)
        runBlocking { store.accounts("s1").first() }
        assertEquals("tok-A", store.cachedToken("s1", "a"))
        assertTrue("Token=\"tok-A\"" in sentHeader("a"))
    }
}
