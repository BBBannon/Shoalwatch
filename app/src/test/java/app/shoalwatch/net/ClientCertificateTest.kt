package app.shoalwatch.net

import app.shoalwatch.core.AppError
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509KeyManager

/**
 * Review I1: a server that needs a client certificate is recognised structurally (the TLS layer records that the
 * server asked for one), not by reading exception text, and reported as `CLIENT_CERT_REQUIRED` through the
 * normal `AppError.from` mapping, on TLS 1.3 and TLS 1.2 alike. A server that only asks optionally and then serves
 * the request is not affected.
 */
class ClientCertificateTest {
    private val servers = mutableListOf<MockWebServer>()
    private val root = HeldCertificate.Builder().certificateAuthority(0).build()
    private val leaf = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").signedBy(root).build()
    private val clientRoot = HeldCertificate.Builder().certificateAuthority(0).build()
    private val clientLeaf = HeldCertificate.Builder().commonName("phone").signedBy(clientRoot).build()
    private val trustsRoot = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build().trustManager

    @After fun stop() = servers.forEach { it.shutdown() }

    /** Only the client certificate held under "phone-cert" exists; it is what KeyChain would return for that alias. */
    private val factory = HttpClientFactory(trustsRoot, { alias ->
        require(alias == "phone-cert")
        HandshakeCertificates.Builder().heldCertificate(clientLeaf).build().keyManager
    })

    private fun server(
        protocol: String? = null,
        require: Boolean = true,
        response: MockResponse = MockResponse().setBody("ok"),
        then: List<MockResponse> = emptyList(),
        http1: Boolean = false,
    ): MockWebServer {
        val handshake = HandshakeCertificates.Builder()
            .heldCertificate(leaf, root.certificate)
            .addTrustedCertificate(clientRoot.certificate)
            .build()
        return MockWebServer().apply {
            val factory = handshake.sslSocketFactory()
            useHttps(if (protocol == null) factory else OnlyProtocol(factory, protocol), false)
            if (http1) protocols = listOf(okhttp3.Protocol.HTTP_1_1)
            if (require) requireClientAuth() else requestClientAuth()
            enqueue(response)
            then.forEach { enqueue(it) }
            start()
            servers += this
        }
    }

    private fun client(s: MockWebServer, alias: String? = null): OkHttpClient = factory.clientFor(
        ServerNetConfig(setOf(ServerAddress.parse("https://127.0.0.1:${s.port}").authority), false, null, alias, emptyMap()) { null },
    )

    private fun get(c: OkHttpClient, s: MockWebServer) =
        c.newCall(Request.Builder().url("https://127.0.0.1:${s.port}/System/Info/Public").build()).execute().use { it.code to it.body.string() }

    private fun kindOf(block: () -> Unit): AppError.Kind {
        try {
            block()
        } catch (e: IOException) {
            return AppError.from(e).kind
        }
        fail("expected a failure")
        error("unreachable")
    }

    @Test fun aServerThatRequiresAClientCertificateIsReportedAsSoOnTls13() {
        val s = server("TLSv1.3")
        assertEquals(AppError.Kind.CLIENT_CERT_REQUIRED, kindOf { get(client(s), s) })
    }

    @Test fun aServerThatRequiresAClientCertificateIsReportedAsSoOnTls12() {
        val s = server("TLSv1.2")
        assertEquals(AppError.Kind.CLIENT_CERT_REQUIRED, kindOf { get(client(s), s) })
    }

    @Test fun aServerThatRequiresAClientCertificateIsReportedAsSoWithTheDefaultProtocols() {
        val s = server()
        assertEquals(AppError.Kind.CLIENT_CERT_REQUIRED, kindOf { get(client(s), s) })
    }

    @Test fun aServerThatOnlyAsksOptionallyAndServesTheRequestIsFine() {
        val s = server(require = false)
        assertEquals(200 to "ok", get(client(s), s))
    }

    @Test fun aProxyThatAsksAndThenRefusesWith400IsReportedAsNeedingACertificate() {
        // nginx with "ssl_verify_client optional" + a 400 "No required SSL certificate was sent".
        val s = server(require = false, response = MockResponse().setResponseCode(400).setBody("No required SSL certificate was sent"))
        assertEquals(AppError.Kind.CLIENT_CERT_REQUIRED, kindOf { get(client(s), s) })
    }

    @Test fun a400FromAServerThatNeverAskedIsJustA400() {
        val s = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(leaf, root.certificate).build().sslSocketFactory(), false)
            enqueue(MockResponse().setResponseCode(400))
            start()
            servers += this
        }
        assertEquals(400, get(client(s), s).first)
    }

    @Test fun withTheChosenCertificateTheServerAcceptsTheConnection() {
        val s = server()
        assertEquals(200 to "ok", get(client(s, alias = "phone-cert"), s))
    }

    @Test fun aRefusalWithACertificateConfiguredIsNotReportedAsMissingOne() {
        val other = HttpClientFactory(trustsRoot, { _: String ->
            // A certificate the server does not trust.
            HandshakeCertificates.Builder().heldCertificate(HeldCertificate.Builder().commonName("stranger").build()).build().keyManager as X509KeyManager
        })
        val s = server()
        val c = other.clientFor(ServerNetConfig(setOf(ServerAddress.parse("https://127.0.0.1:${s.port}").authority), false, null, "phone-cert", emptyMap()) { null })
        assertEquals(false, kindOf { get(c, s) } == AppError.Kind.CLIENT_CERT_REQUIRED)
    }

    // ---- only a failure that can be blamed on the missing certificate is remapped (review F5)

    @Test fun aGenuine403OnAnAlreadyProvenConnectionIsForbiddenNotAMissingCertificate() {
        // A proxy with optional client auth: it asks, serves the first request, and later Jellyfin itself answers 403.
        val s = server(require = false, then = listOf(MockResponse().setResponseCode(403).setBody("no")), http1 = true)
        val c = client(s)
        assertEquals(200 to "ok", get(c, s))
        val (code, _) = get(c, s) // a response, not an exception
        assertEquals(403, code)
        assertEquals(AppError.Kind.FORBIDDEN, AppError.fromHttpStatus(code).kind)
    }

    @Test fun aReadTimeoutAfterASuccessfulFirstResponseIsATimeoutNotAMissingCertificate() {
        val s = server(require = false, then = listOf(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)), http1 = true)
        val c = client(s)
        assertEquals(200 to "ok", get(c, s))
        val quick = c.newBuilder().readTimeout(300, TimeUnit.MILLISECONDS).build()
        assertEquals(AppError.Kind.TIMEOUT, kindOf { get(quick, s) })
    }

    @Test fun aFirstResponseOf403OnAFreshConnectionWhereACertificateWasAskedForIsReportedAsNeedingOne() {
        val s = server(require = false, response = MockResponse().setResponseCode(403))
        assertEquals(AppError.Kind.CLIENT_CERT_REQUIRED, kindOf { get(client(s), s) })
    }

    @Test fun aServerThatNeverAskedIsNeverBlamedOnACertificate() {
        val s = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(leaf, root.certificate).build().sslSocketFactory(), false)
            protocols = listOf(okhttp3.Protocol.HTTP_1_1)
            enqueue(MockResponse().setResponseCode(403))
            enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            start()
            servers += this
        }
        val c = client(s)
        assertEquals(403, get(c, s).first)
        assertEquals(AppError.Kind.TIMEOUT, kindOf { get(c.newBuilder().readTimeout(300, TimeUnit.MILLISECONDS).build(), s) })
    }

    /** Server side only: pins the TLS version MockWebServer negotiates. */
    private class OnlyProtocol(private val d: SSLSocketFactory, private val protocol: String) : SSLSocketFactory() {
        private fun Socket.pinned(): Socket = apply { (this as SSLSocket).enabledProtocols = arrayOf(protocol) }
        override fun getDefaultCipherSuites(): Array<String> = d.defaultCipherSuites
        override fun getSupportedCipherSuites(): Array<String> = d.supportedCipherSuites
        override fun createSocket(s: Socket?, host: String?, port: Int, autoClose: Boolean): Socket = d.createSocket(s, host, port, autoClose).pinned()
        override fun createSocket(host: String?, port: Int): Socket = d.createSocket(host, port).pinned()
        override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = d.createSocket(host, port, localHost, localPort).pinned()
        override fun createSocket(host: InetAddress?, port: Int): Socket = d.createSocket(host, port).pinned()
        override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
            d.createSocket(address, port, localAddress, localPort).pinned()
    }
}
