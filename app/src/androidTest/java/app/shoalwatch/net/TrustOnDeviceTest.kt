package app.shoalwatch.net

import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

/**
 * Real Android TLS stack (Conscrypt, platform trust store) against a MockWebServer running inside
 * the instrumentation process. Run on a device by the controller; the server is reached at 127.0.0.1.
 * Network calls run on a background thread because Android refuses them on the main thread.
 */
class TrustOnDeviceTest {
    private val factory = HttpClientFactory(InstrumentationRegistry.getInstrumentation().targetContext)
    private val servers = mutableListOf<MockWebServer>()

    @After fun stop() = servers.forEach { it.shutdown() }

    private fun serverWith(cert: HeldCertificate, vararg chain: HeldCertificate): MockWebServer {
        val hs = HandshakeCertificates.Builder().heldCertificate(cert, *chain.map { it.certificate }.toTypedArray()).build()
        return MockWebServer().apply {
            useHttps(hs.sslSocketFactory(), false)
            enqueue(MockResponse().setBody("ok"))
            start()
            servers += this
        }
    }

    private fun selfSigned() = HeldCertificate.Builder().commonName("lan").addSubjectAlternativeName("127.0.0.1").build()

    private fun config(s: MockWebServer, pin: String? = null) = ServerNetConfig(
        authorities = setOf(ServerAddress.parse("https://127.0.0.1:${s.port}").authority),
        allowCleartext = false,
        pinnedSpkiSha256 = pin,
        clientCertAlias = null,
        customHeaders = emptyMap(),
        authHeader = { null },
    )

    private fun get(client: OkHttpClient, s: MockWebServer): Int = runBlocking(kotlinx.coroutines.Dispatchers.IO) {
        client.newCall(Request.Builder().url("https://127.0.0.1:${s.port}/x").build()).execute().use { it.code }
    }

    private fun failureKind(client: OkHttpClient, s: MockWebServer): AppError.Kind {
        try {
            get(client, s)
        } catch (e: IOException) {
            return AppError.from(e).kind
        }
        fail("expected the call to fail")
        error("unreachable")
    }

    @Test fun selfSignedWithoutPinIsUntrusted() {
        val s = serverWith(selfSigned())
        assertEquals(AppError.Kind.TLS_UNTRUSTED, failureKind(factory.clientFor(config(s)), s))
    }

    @Test fun selfSignedWithCorrectPinIsAccepted() {
        val cert = selfSigned()
        val s = serverWith(cert)
        assertEquals(200, get(factory.clientFor(config(s, spkiSha256Base64(cert.certificate))), s))
    }

    @Test fun selfSignedWithWrongPinIsPinChanged() {
        val s = serverWith(selfSigned())
        val otherKey = spkiSha256Base64(selfSigned().certificate)
        assertEquals(AppError.Kind.TLS_PIN_CHANGED, failureKind(factory.clientFor(config(s, otherKey)), s))
        assertEquals(0, s.requestCount)
    }

    @Test fun probeReturnsTheLeafAndSendsNothing() {
        val cert = selfSigned()
        val s = serverWith(cert)
        val info = runBlocking { factory.probeCertificate(ServerAddress.parse("https://127.0.0.1:${s.port}")) }
        assertNotNull(info)
        assertEquals(spkiSha256Base64(cert.certificate), info!!.spkiSha256)
        assertEquals(0, s.requestCount)
    }

    @Test fun certificateFromAnUnknownCaIsRejected() {
        val unknownCa = HeldCertificate.Builder().certificateAuthority(0).build()
        val leaf = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").signedBy(unknownCa).build()
        val s = serverWith(leaf, unknownCa)
        assertEquals(AppError.Kind.TLS_UNTRUSTED, failureKind(factory.clientFor(config(s)), s))
    }
}
