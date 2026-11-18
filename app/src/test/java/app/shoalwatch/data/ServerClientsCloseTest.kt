package app.shoalwatch.data

import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.ServerClients
import app.shoalwatch.data.servers.ServerNet
import app.shoalwatch.data.servers.ServerNetDraft
import app.shoalwatch.net.HttpClientFactory
import app.shoalwatch.net.ServerAddress
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Closing a pooled TLS connection writes a close_notify to the network, which Android refuses on the main thread
 * (`NetworkOnMainThreadException`). [ServerClients.evict] runs from the account and settings hooks, which can be on the main
 * thread (it failed a Quick Connect sign-in on an https server, 18/11/2026), so connections are only ever closed off the
 * caller's thread.
 */
class ServerClientsCloseTest {
    private val web = MockWebServer().apply { start() }
    private val address = ServerAddress.parse("http://127.0.0.1:${web.port}")
    private val device = DeviceIdentity("dev-1", "Pixel", "Shoalwatch", "0.1")
    private val net = ServerNet.anonymous(listOf(address), ServerNetDraft(allowCleartext = true), device)
    private val queued = mutableListOf<Runnable>()
    private val clients = ServerClients(
        HttpClientFactory(HandshakeCertificates.Builder().build().trustManager, { error("no client certificates here") }),
        closeLater = { queued += it },
    )

    @After fun stop() = web.shutdown()

    private fun call(client: okhttp3.OkHttpClient) {
        web.enqueue(MockResponse().setBody("ok"))
        client.newCall(Request.Builder().url(web.url("/System/Info/Public")).build()).execute().use { it.body?.string() }
    }

    @Test fun evictNeverClosesAConnectionOnTheCallersThread() {
        val client = clients.clientFor(net)
        call(client)
        assertEquals("the call left one idle connection", 1, client.connectionPool.connectionCount())

        clients.evict("s1", listOf(address))

        assertEquals("nothing closed inline", 1, client.connectionPool.connectionCount())
        assertEquals("the close was handed on", 1, queued.size)
        queued.forEach { it.run() }
        assertEquals("closed once the handed-on work runs", 0, client.connectionPool.connectionCount())
    }

    @Test fun aOneOffClientIsClosedOffTheCallersThreadToo() = runBlocking {
        var used: okhttp3.OkHttpClient? = null
        clients.oneOff(net) { used = it; call(it) }

        assertEquals("nothing closed inline", 1, used!!.connectionPool.connectionCount())
        assertEquals(1, queued.size)
        queued.forEach { it.run() }
        assertEquals(0, used!!.connectionPool.connectionCount())
    }

    @Test fun theDefaultCloserRunsOnABackgroundThread() {
        val ran = CountDownLatch(1)
        var thread: Thread? = null
        ServerClients.closeInBackground(Runnable { thread = Thread.currentThread(); ran.countDown() })
        assertTrue(ran.await(5, TimeUnit.SECONDS))
        assertNotSame(Thread.currentThread(), thread)
        assertTrue("a daemon thread never keeps the process alive", thread!!.isDaemon)
    }
}
