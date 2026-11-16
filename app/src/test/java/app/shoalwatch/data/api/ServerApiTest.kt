package app.shoalwatch.data.api

import app.shoalwatch.S1
import app.shoalwatch.data.servers.Session
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
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.KeyGenerator


import app.shoalwatch.data.servers.StoreCatalog
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertNull
class ServerApiTest : ServerApiTestBase() {
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

    @Test fun aSessionPrintsNothing() {
        assertFalse(session.toString().contains(S1))
        assertFalse(session.toString().contains(U1))
    }

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

    private fun kindFor(code: Int, location: String? = null): AppError.Kind {
        web.enqueue(MockResponse().setResponseCode(code).apply { if (location != null) setHeader("Location", location) })
        return failure { getThings() }.kind
    }

    @Test fun statusCodesMapToKinds() {
        assertEquals(AppError.Kind.FORBIDDEN, kindFor(403))
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, kindFor(404))
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, kindFor(301))
        assertEquals(AppError.Kind.SERVER_ERROR, kindFor(500))
        assertEquals(AppError.Kind.SERVER_ERROR, kindFor(503))
        assertEquals(AppError.Kind.SERVER_ERROR, kindFor(400))
        assertEquals(AppError.Kind.SERVER_ERROR, kindFor(418))
        assertEquals("a response is an answer: never retried", 0, selector.refreshes.get())
        assertEquals(7, web.requestCount)
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

    // ---- a rejected sign-in (401) ----

    @Test fun aRejectedSignInStopsTheLoopAndNeedsANewSignIn() {
        web.enqueue(MockResponse().setResponseCode(401))
        val e = failure { getThings() }
        assertEquals(AppError.Kind.UNAUTHORISED, e.kind)
        assertEquals("Your sign-in has expired. Sign in again.", e.userMessage)
        assertEquals(1, web.requestCount)
        assertEquals("no more requests with a dead token", AppError.Kind.NOT_SIGNED_IN, failure { getThings() }.kind)
        assertEquals(1, web.requestCount)
        assertNull(runBlocking { StoreCatalog(store).activeSession().first() })
        assertTrue(runBlocking { store.accounts(S1).first() }.none { it.userId == U1 })

        runBlocking { store.upsertAccount(Account(S1, U1, "ben", "tok-new"), makeActive = true) }
        web.enqueue(json("[]"))
        getThings()
        web.takeRequest()
        assertTrue(web.takeRequest().getHeader("Authorization")!!.contains("Token=\"tok-new\""))
        assertEquals(Session(S1, U1), runBlocking { StoreCatalog(store).activeSession().first() })
    }

    @Test fun aForbiddenAnswerDoesNotSignAnyoneOut() {
        web.enqueue(MockResponse().setResponseCode(403)); web.enqueue(json("[]"))
        assertEquals(AppError.Kind.FORBIDDEN, failure { getThings() }.kind)
        getThings()
        assertEquals(2, web.requestCount)
        assertEquals(Session(S1, U1), runBlocking { StoreCatalog(store).activeSession().first() })
    }

    @Test fun aRejectionOfAnOlderSignInDoesNotSignOutTheNewOne() {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                runBlocking { store.upsertAccount(Account(S1, U1, "ben", "tok-new"), makeActive = true) } // signed in again while this was in flight
                return MockResponse().setResponseCode(401)
            }
        }
        assertEquals(AppError.Kind.UNAUTHORISED, failure { getThings() }.kind)
        assertEquals("tok-new", store.cachedToken(S1, U1))
    }

    @Test fun theServersSettingsAreReadAfterTheAddressIsVerified() {
        selector.answer = { saveServer(cleartext = false); online() } // consent withdrawn while waiting
        assertEquals(AppError.Kind.CLEARTEXT_BLOCKED, failure { getThings() }.kind)
        assertEquals(0, web.requestCount)
    }

}
