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
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.KeyGenerator


import java.io.IOException
class ServerApiRetryTest : ServerApiTestBase() {
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
        assertEquals("each failed attempt may ask for a re-check of its own connection", 2, selector.refreshes.get())
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

    @Test fun aRetryFollowsAChangedAddress() {
        val other = MockWebServer().apply { start() }
        try {
            val b = ServerAddress.parse("http://127.0.0.1:${other.port}")
            saveServer(away = b)
            web.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
            }
            other.enqueue(json("""[{"Name":"b"}]"""))
            val atRetry = AtomicInteger(-1)
            selector.answer = { n -> if (n == 1) online() else { atRetry.set(web.requestCount); Connection.Online(b, isHome = false) } }
            assertEquals("b", getThings().single().name)
            assertEquals("the first address received nothing after the retry began", atRetry.get(), web.requestCount)
            assertEquals(1, other.requestCount)
            assertTrue(other.takeRequest().getHeader("Authorization")!!.contains("Token=\"tok-1\""))
        } finally {
            other.shutdown()
        }
    }

    @Test fun getBytesRetriesLikeGet() {
        val failing = AtomicBoolean(true)
        failWhile(failing, ok = MockResponse().setBody("abc"))
        selector.answer = { n -> if (n >= 2) failing.set(false); online() }
        assertEquals("abc", runBlocking { api.getBytes(session, "/x", maxBytes = 10) { s, _ -> s.readUtf8() } })
        assertEquals(2, selector.asks.get())
        assertEquals(1, selector.refreshes.get())
    }

    @Test fun failuresAskOnlyForTheirOwnConnectionNeverForTheGlobalRefresh() {
        failWhile(AtomicBoolean(true))
        failure { getThings() }
        assertEquals(0, selector.globalRefreshes.get())
        assertEquals(online().address, selector.lastFailed.get()!!.address)
    }

    @Test fun anIoExceptionFromTheCallersReaderIsNotANetworkFailure() {
        web.enqueue(MockResponse().setBody("abc"))
        try {
            runBlocking { api.getBytes(session, "/x", maxBytes = 10) { _, _ -> throw IOException("disk full") } }
            fail("expected the reader's exception")
        } catch (e: IOException) {
            assertEquals("disk full", e.message)
        }
        assertEquals(1, selector.asks.get())
        assertEquals(0, selector.refreshes.get())
    }

    @Test fun aConnectionFailingMidBodyIsANetworkFailureWithoutARetry() {
        web.enqueue(MockResponse().setBody("x".repeat(100)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val e = failure { runBlocking { api.getBytes(session, "/x", maxBytes = 1000) { s, _ -> s.readByteArray() } } }
        assertEquals(AppError.Kind.NETWORK, e.kind)
        assertEquals(1, selector.asks.get())
        assertEquals(1, selector.refreshes.get())
    }

}
