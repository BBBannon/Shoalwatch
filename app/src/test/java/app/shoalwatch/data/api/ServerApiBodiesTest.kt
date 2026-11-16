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


import okio.GzipSink
import okio.buffer
class ServerApiBodiesTest : ServerApiTestBase() {
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

    private fun gzipped(raw: ByteArray, type: String): MockResponse {
        val packed = Buffer()
        GzipSink(packed).buffer().use { it.write(raw) }
        assertTrue("compresses well below the limits used here", packed.size < 1000)
        return MockResponse().setHeader("Content-Encoding", "gzip").setHeader("Content-Type", type).setBody(packed)
    }

    @Test fun theSizeLimitAppliesToTheDecodedBytesOfAGzipJsonBody() {
        val text = """[{"Name":"${"x".repeat(5000)}"}]"""
        web.enqueue(gzipped(text.toByteArray(), "application/json")); web.enqueue(gzipped(text.toByteArray(), "application/json"))
        val e = failure { runBlocking { api.get(session, "/Things", emptyList(), things(), maxBytes = 1000) } }
        assertEquals(AppError.Kind.SERVER_ERROR, e.kind)
        assertEquals(5000, runBlocking { api.get(session, "/Things", emptyList(), things(), maxBytes = text.length.toLong()) }.single().name!!.length)
    }

    @Test fun theSizeLimitAppliesToTheDecodedBytesOfAGzipBinaryBody() {
        web.enqueue(gzipped(ByteArray(10_000), "image/png"))
        val e = failure { runBlocking { api.getBytes(session, "/x", maxBytes = 1000) { s, _ -> s.readByteArray() } } }
        assertEquals(AppError.Kind.SERVER_ERROR, e.kind)
    }

    @Test fun anExplicitJsonNullForADefaultedFieldIsNotSilentlyCoerced() {
        web.enqueue(json("""[{"Name":"a","Count":null}]"""))
        assertEquals(AppError.Kind.SERVER_ERROR, failure { getThings() }.kind)
    }

}
