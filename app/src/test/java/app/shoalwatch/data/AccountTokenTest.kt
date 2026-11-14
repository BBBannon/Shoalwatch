package app.shoalwatch.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.AuthRepository
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.JellyfinGatewayImpl
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerClients
import app.shoalwatch.data.servers.ServerNet
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.net.HttpClientFactory
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.security.SecretBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Assert.fail
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.KeyGenerator

private const val S = "0123abcd456789ef0123456789abcdef"

/** Signed-in clients read the token live: assertions are on the requests real OkHttp actually sends. */
@RunWith(RobolectricTestRunner::class)
class AccountTokenTest {
    private val web = MockWebServer()
    private val device = DeviceIdentity("dev-1", "Pixel", "Shoalwatch", "0.1")
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val clients = ServerClients(HttpClientFactory(HandshakeCertificates.Builder().build().trustManager, { error("no client certificates here") }))
    private val gateway = JellyfinGatewayImpl(clients)
    private lateinit var db: AppDatabase
    private lateinit var store: ServerStore
    private lateinit var server: Server

    @Before fun open() {
        web.start()
        val address = ServerAddress.parse("http://127.0.0.1:${web.port}")
        server = Server(S, "Den", "10.10.3", address, null, true, null, null, emptyMap())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        store = ServerStore(db, SecretBox { key }, clients::evict)
        runBlocking { store.upsertServer(server) }
    }

    @After fun close() { db.close(); web.shutdown() }

    /** The selector has verified the only (http) address; AuthRepository still checks the identity on it first. */
    private fun verifiedHome() = StubConnections { Connection.Online(server.homeAddress!!, isHome = true) }

    private fun enqueueIdentity() = web.enqueue(
        MockResponse().setHeader("Content-Type", "application/json").setBody("""{"Id":"$S","ServerName":"Den","Version":"10.10.3"}"""),
    )

    private fun signIn(userId: String, token: String) = runBlocking { store.upsertAccount(Account(S, userId, userId, token), makeActive = false) }

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
        val repo = AuthRepository(gateway, store, device, verifiedHome())

        enqueueIdentity()
        web.enqueue(MockResponse().setResponseCode(204))
        runBlocking { repo.signOut(S, "a") }
        web.takeRequest() // the anonymous identity check on the address, just before the token
        assertTrue("Token=\"tok-A\"" in web.takeRequest().getHeader("Authorization")!!)

        // Refused, not sent without a token: the server sees nothing more.
        assertEquals(AppError.Kind.NOT_SIGNED_IN, signedOutError("a").kind)
        assertEquals(3, web.requestCount)
    }

    @Test fun reSigningInIsPickedUpByTheCachedClient() {
        signIn("a", "tok-OLD")
        assertTrue("Token=\"tok-OLD\"" in sentHeader("a"))
        signIn("a", "tok-NEW")
        val header = sentHeader("a")
        assertTrue(header, "Token=\"tok-NEW\"" in header)
        assertFalse(header, "tok-OLD" in header)
    }

    private fun restart() {
        store = ServerStore(db, SecretBox { key }, clients::evict)
    }

    private fun signedOutError(userId: String): AppError {
        try {
            runBlocking { gateway.logout(server.homeAddress!!, ServerNet.authenticated(server.addresses, server, userId, device, store::cachedToken)) }
        } catch (e: AppError) {
            return e
        }
        fail("expected AppError")
        error("unreachable")
    }

    @Test fun afterARestartTheTokenIsOnlyAvailableOnceWarmed() {
        signIn("a", "tok-A")
        restart()
        assertNull(store.cachedToken(S, "a"))
        runBlocking { store.warmTokens() }
        assertEquals("tok-A", store.cachedToken(S, "a"))
        assertTrue("Token=\"tok-A\"" in sentHeader("a"))
        runBlocking { store.warmTokens() } // safe to repeat
        assertEquals("tok-A", store.cachedToken(S, "a"))
    }

    @Test fun readingAccountsNeverFillsTheTokenMap() {
        signIn("a", "tok-A")
        restart()
        runBlocking {
            assertEquals(1, store.accounts(S).first().size)
            store.active()
            store.activeAccount.first()
        }
        assertNull(store.cachedToken(S, "a"))
    }

    @Test fun aRemovedAccountStaysGoneEvenWhenRowsAreReadAfterwards() {
        signIn("a", "tok-A")
        runBlocking {
            store.removeAccount(S, "a")
            store.accounts(S).first()
            store.active()
            store.activeAccount.first()
        }
        assertNull(store.cachedToken(S, "a"))
    }

    @Test fun anAuthenticatedRequestWithNoTokenIsRefusedAndNothingIsSent() {
        val error = signedOutError("a")
        assertEquals(AppError.Kind.NOT_SIGNED_IN, error.kind)
        assertEquals("You're signed out. Sign in again to continue.", error.userMessage)
        assertEquals(0, web.requestCount)
    }

    @Test fun anAccountSignedOutIsRefusedNotSentAnonymously() {
        signIn("a", "tok-A")
        sentHeader("a")
        runBlocking { store.removeAccount(S, "a") }
        assertEquals(AppError.Kind.NOT_SIGNED_IN, signedOutError("a").kind)
        assertEquals(1, web.requestCount)
    }

    @Test fun warmingNeverBringsBackAnAccountThatWasRemoved() {
        runBlocking { store.removeAccount(S, "ghost") }
        signIn("a", "tok-A")
        signIn("b", "tok-B")
        runBlocking { store.removeAccount(S, "a") }
        restart()
        runBlocking { store.warmTokens() }
        assertNull(store.cachedToken(S, "a"))
        assertEquals("tok-B", store.cachedToken(S, "b"))
    }

    @Test fun aWarmRacingARemovalNeverLeavesTheRemovedToken() {
        repeat(150) { i ->
            val user = "u$i"
            signIn(user, "tok-$i")
            restart()
            runBlocking(Dispatchers.Default) {
                val warm = launch { store.warmTokens() }
                val remove = launch { store.removeAccount(S, user) }
                warm.join(); remove.join()
            }
            assertNull("iteration $i", store.cachedToken(S, user))
        }
    }

    @Test fun removingAServerOnlyDropsItsOwnTokensEvenWhenIdsLookLikePrefixes() {
        val inner = server.copy(id = "$S:x")
        runBlocking {
            store.upsertServer(inner)
            store.upsertAccount(Account("$S:x", "u", "u", "tok-inner"), makeActive = false)
            store.upsertAccount(Account(S, "x:u", "x:u", "tok-outer"), makeActive = false)
            store.removeServer(S)
        }
        assertNull(store.cachedToken(S, "x:u"))
        assertEquals("tok-inner", store.cachedToken("$S:x", "u"))
    }

    @Test fun signOutSendsTheTokenItRevokesEvenFromAColdStoreAndLeavesNothingBehind() {
        signIn("a", "tok-A")
        restart()
        enqueueIdentity()
        web.enqueue(MockResponse().setResponseCode(204))
        runBlocking { AuthRepository(gateway, store, device, verifiedHome()).signOut(S, "a") }
        web.takeRequest() // identity check
        assertTrue("Token=\"tok-A\"" in web.takeRequest().getHeader("Authorization")!!)
        assertNull(store.cachedToken(S, "a"))
        runBlocking { assertTrue(store.accounts(S).first().isEmpty()) }
        // The client used for the logout was dropped with the account: a later call is refused, not sent with the old token.
        assertEquals(AppError.Kind.NOT_SIGNED_IN, signedOutError("a").kind)
        assertEquals(2, web.requestCount)
    }
}
