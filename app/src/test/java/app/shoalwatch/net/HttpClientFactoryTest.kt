package app.shoalwatch.net

import app.shoalwatch.core.AppError
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import javax.net.ssl.X509TrustManager

class HttpClientFactoryTest {
    private val servers = mutableListOf<MockWebServer>()
    private val token = "MediaBrowser Token=\"secret\""

    private val root = HeldCertificate.Builder().certificateAuthority(0).build()
    private val noTrust = HandshakeCertificates.Builder().addTrustedCertificate(HeldCertificate.Builder().certificateAuthority(0).build().certificate).build().trustManager
    private val trustsRoot = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build().trustManager

    private fun factory(system: X509TrustManager = noTrust, probeDeadlineMs: Long = 10_000) =
        HttpClientFactory(system, { error("no client certificates in these tests") }, probeDeadlineMs)

    private fun httpsServer(cert: HeldCertificate, chain: List<HeldCertificate> = emptyList()): MockWebServer {
        val hs = HandshakeCertificates.Builder().heldCertificate(cert, *chain.map { it.certificate }.toTypedArray()).build()
        return MockWebServer().apply {
            useHttps(hs.sslSocketFactory(), false)
            enqueue(MockResponse().setBody("ok"))
            start()
            servers += this
        }
    }

    private fun authorityOf(s: MockWebServer, scheme: String) =
        ServerAddress.parse("$scheme://127.0.0.1:${s.port}").authority

    private fun config(
        s: MockWebServer, pin: String? = null, cleartext: Boolean = false, header: String? = token,
        scheme: String = "https",
    ) = ServerNetConfig(setOf(authorityOf(s, scheme)), cleartext, pin, null, emptyMap()) { header }

    private fun get(client: OkHttpClient, s: MockWebServer, scheme: String = "https") =
        client.newCall(Request.Builder().url("$scheme://127.0.0.1:${s.port}/x").build()).execute().use { it.body.string() }

    private fun kindOf(block: () -> Unit): AppError.Kind {
        try {
            block()
        } catch (e: IOException) {
            return AppError.from(e).kind
        }
        fail("expected a failure")
        error("unreachable")
    }

    @After fun stop() = servers.forEach { it.shutdown() }

    private fun selfSigned(san: String = "127.0.0.1") =
        HeldCertificate.Builder().commonName("lan").addSubjectAlternativeName(san).build()

    @Test fun `self-signed server is rejected without a pin`() {
        val s = httpsServer(selfSigned())
        assertEquals(AppError.Kind.TLS_UNTRUSTED, kindOf { get(factory().clientFor(config(s)), s) })
    }

    @Test fun `self-signed server is accepted with the right pin`() {
        val cert = selfSigned()
        val s = httpsServer(cert)
        assertEquals("ok", get(factory().clientFor(config(s, pin = spkiSha256Base64(cert.certificate))), s))
    }

    @Test fun `self-signed server with a wrong pin is TLS_PIN_CHANGED`() {
        val s = httpsServer(selfSigned())
        val wrong = spkiSha256Base64(selfSigned().certificate)
        assertEquals(AppError.Kind.TLS_PIN_CHANGED, kindOf { get(factory().clientFor(config(s, pin = wrong)), s) })
        assertEquals(0, s.requestCount)
    }

    @Test fun `properly chained server is accepted with no pin`() {
        val leaf = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").signedBy(root).build()
        val s = httpsServer(leaf, listOf(root))
        assertEquals("ok", get(factory(trustsRoot).clientFor(config(s)), s))
    }

    @Test fun `pinned self-signed certificate is accepted although its name does not match`() {
        val cert = selfSigned(san = "other.example")
        val s = httpsServer(cert)
        assertEquals("ok", get(factory().clientFor(config(s, pin = spkiSha256Base64(cert.certificate))), s))
    }

    @Test fun `unpinned self-signed certificate with a wrong name is rejected`() {
        val s = httpsServer(selfSigned(san = "other.example"))
        assertEquals(AppError.Kind.TLS_UNTRUSTED, kindOf { get(factory().clientFor(config(s)), s) })
    }

    @Test fun `system-trusted certificate for a different host is rejected even when a pin is set`() {
        val leaf = HeldCertificate.Builder().addSubjectAlternativeName("other.example").signedBy(root).build()
        val s = httpsServer(leaf, listOf(root))
        val unrelatedPin = spkiSha256Base64(selfSigned().certificate)
        assertEquals(
            AppError.Kind.TLS_UNTRUSTED,
            kindOf { get(factory(trustsRoot).clientFor(config(s, pin = unrelatedPin)), s) },
        )
        assertEquals(0, s.requestCount)
        assertEquals(AppError.Kind.TLS_UNTRUSTED, kindOf { get(factory(trustsRoot).clientFor(config(s)), s) })
    }

    @Test fun `clients share one connection pool and dispatcher and use no cache or cookies`() {
        val f = factory()
        val s = MockWebServer().apply { start(); servers += this }
        val a = f.clientFor(config(s, scheme = "http", cleartext = true))
        val b = f.clientFor(config(s, scheme = "http", cleartext = true))
        assertSame(a.connectionPool, b.connectionPool)
        assertSame(a.dispatcher, b.dispatcher)
        assertNull(a.cache)
        assertEquals(okhttp3.CookieJar.NO_COOKIES, a.cookieJar)
        assertEquals(10_000, a.connectTimeoutMillis)
        assertEquals(30_000, a.readTimeoutMillis)
        assertEquals(30_000, a.writeTimeoutMillis)
        assertEquals(0, a.callTimeoutMillis)
        assertEquals(false, a.followSslRedirects)
    }

    @Test fun `http is refused when cleartext is not allowed`() {
        val s = MockWebServer().apply { start(); servers += this }
        val client = factory().clientFor(config(s, scheme = "http", cleartext = false))
        assertEquals(AppError.Kind.CLEARTEXT_BLOCKED, kindOf { get(client, s, "http") })
        assertEquals(0, s.requestCount)
    }

    @Test fun `Authorization reaches the configured server but not a redirect target`() {
        val a = MockWebServer().apply { start(); servers += this }
        val b = MockWebServer().apply { start(); servers += this }
        b.enqueue(MockResponse().setBody("there"))
        a.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://127.0.0.1:${b.port}/y"))
        val client = factory().clientFor(config(a, scheme = "http", cleartext = true))
        assertEquals("there", get(client, a, "http"))
        assertEquals(token, a.takeRequest().getHeader("Authorization"))
        assertNull(b.takeRequest().getHeader("Authorization"))
    }

    @Test fun `probe returns the leaf without sending any request`() {
        val cert = selfSigned()
        val s = httpsServer(cert)
        val info = runBlocking { factory().probeCertificate(ServerAddress.parse("https://127.0.0.1:${s.port}")) }
        assertNotNull(info)
        assertEquals(spkiSha256Base64(cert.certificate), info!!.spkiSha256)
        assertEquals(cert.certificate.notAfter.toInstant(), info.notAfter)
        assertEquals(0, s.requestCount)
    }

    @Test fun `probe returns null for http and maps network failures`() {
        val f = factory()
        assertNull(runBlocking { f.probeCertificate(ServerAddress.parse("http://127.0.0.1:1")) })
        try {
            runBlocking { f.probeCertificate(ServerAddress.parse("https://127.0.0.1:1")) }
            fail()
        } catch (e: AppError) {
            assertEquals(AppError.Kind.NETWORK, e.kind)
        }
    }

    @Test fun `probe gives up on a server that never replies, within the deadline, and closes the socket`() {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { silent ->
            val accepted = java.util.concurrent.CompletableFuture<java.net.Socket>()
            Thread { accepted.complete(silent.accept()) }.start()
            val started = System.nanoTime()
            try {
                runBlocking { factory(probeDeadlineMs = 800).probeCertificate(ServerAddress.parse("https://127.0.0.1:${silent.localPort}")) }
                fail()
            } catch (e: AppError) {
                assertEquals(AppError.Kind.TIMEOUT, e.kind)
            }
            val elapsedMs = (System.nanoTime() - started) / 1_000_000
            org.junit.Assert.assertTrue("took $elapsedMs ms", elapsedMs < 1_800)
            val server = accepted.get(2, java.util.concurrent.TimeUnit.SECONDS)
            server.soTimeout = 2_000
            // The client end was closed, so the server side sees end-of-stream (or a reset) rather than a timeout.
            val closed = try {
                var n = 0
                while (n != -1) n = server.getInputStream().read()
                true
            } catch (_: java.net.SocketTimeoutException) {
                false
            } catch (_: java.io.IOException) {
                true
            }
            assertEquals(true, closed)
            server.close()
        }
    }
}
