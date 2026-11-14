package app.offlinefin.net

import android.content.Context
import app.offlinefin.core.AppError
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/**
 * The only place in the app that constructs an [OkHttpClient]. See "Network security model" in
 * docs/ARCHITECTURE.md for the rules enforced here.
 */
class HttpClientFactory internal constructor(
    private val systemTrust: X509TrustManager,
    private val keyManagerFor: (alias: String) -> X509KeyManager,
    private val probeDeadlineMs: Long = 10_000,
) {
    constructor(context: Context) : this(
        platformTrustManager(),
        { alias -> KeyChainKeyManager(context, alias) },
    )

    /** Shared by every client from [clientFor]: one connection pool and dispatcher, no cache, no cookies. */
    private val base: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(IO_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(IO_TIMEOUT_S, TimeUnit.SECONDS)
        // No call timeout: it would cut off long media downloads.
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        .followSslRedirects(false)
        .connectionSpecs(listOf(TLS_SPEC, ConnectionSpec.CLEARTEXT))
        .build()

    /**
     * Builds a client for one server configuration. Callers must hold ONE client per server
     * configuration and reuse it: every call builds a new `SSLContext`, so connections and TLS
     * sessions are not shared between separately built clients (only the pool and dispatcher are).
     * There is deliberately no cache here.
     */
    fun clientFor(config: ServerNetConfig): OkHttpClient {
        val trustManager = PinnedTrustManager(systemTrust, config.pinnedSpkiSha256)
        val keyManagers: Array<KeyManager>? = config.clientCertAlias?.let { arrayOf(keyManagerFor(it)) }
        val sslContext = SSLContext.getInstance("TLS").apply { init(keyManagers, arrayOf(trustManager), SecureRandom()) }
        val authorities = { config.authorities }

        val cleartextGuard = CleartextGuard { config.allowCleartext }
        val customHeaders = CustomHeaderInterceptor(authorities) { config.customHeaders }
        val auth = AuthInterceptor(authorities) { config.authHeader() }

        // Each guard is both an application and a network interceptor: the network position sees
        // every redirect hop, which is what keeps the token and headers on their own server.
        return base.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .hostnameVerifier(PinAwareHostnameVerifier(config.pinnedSpkiSha256, base.hostnameVerifier))
            .apply {
                for (guard in listOf(cleartextGuard, customHeaders, auth)) {
                    addInterceptor(guard)
                    addNetworkInterceptor(guard)
                }
            }
            .build()
    }

    /**
     * Reads the leaf certificate a server presents, for the trust prompt. The handshake is made to
     * fail on purpose once the certificate has been captured, so no HTTP request, token or header
     * ever travels over this unverified connection. Returns null for `http` addresses.
     *
     * The whole probe (connect and handshake) has a hard deadline of [probeDeadlineMs] (10 s in the app):
     * on expiry or cancellation the socket is closed, which unblocks the worker thread. Name resolution
     * cannot be interrupted, so a stalled DNS lookup leaves one daemon thread behind until it returns,
     * but the caller is released at the deadline.
     *
     * @throws AppError `TIMEOUT` at the deadline, or via [AppError.from] if the server cannot be reached
     *   or does not present a certificate.
     */
    suspend fun probeCertificate(address: ServerAddress): CertificateInfo? {
        if (!address.isSecure) return null
        val plain = Socket()
        try {
            return withTimeout(probeDeadlineMs) {
                suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation { closeQuietly(plain) }
                    thread(isDaemon = true, name = "certificate-probe") {
                        cont.resumeWith(runCatching { probe(address, plain) })
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw AppError.of(AppError.Kind.TIMEOUT, e)
        } finally {
            closeQuietly(plain)
        }
    }

    private fun closeQuietly(socket: Socket) {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    private fun probe(address: ServerAddress, plain: Socket): CertificateInfo {
        val capture = CaptureTrustManager()
        try {
            val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(capture), SecureRandom()) }
            val port = address.authority.substringAfterLast(':').toInt()
            val stepMs = probeDeadlineMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            plain.soTimeout = stepMs
            plain.connect(InetSocketAddress(address.host, port), stepMs)
            (context.socketFactory.createSocket(plain, address.host, port, true) as SSLSocket).use { tls ->
                tls.enabledProtocols = arrayOf(TlsVersion.TLS_1_3.javaName, TlsVersion.TLS_1_2.javaName)
                tls.startHandshake()
            }
        } catch (e: IOException) {
            val leaf = capture.leaf ?: throw AppError.from(e)
            return CertificateInfo(
                subject = leaf.subjectX500Principal.name,
                issuer = leaf.issuerX500Principal.name,
                notAfter = leaf.notAfter.toInstant(),
                spkiSha256 = spkiSha256Base64(leaf),
            )
        }
        // The capture manager always fails the handshake, so reaching here means no certificate was seen.
        throw AppError.of(AppError.Kind.NETWORK)
    }

    /** Records the presented leaf and then always rejects it. */
    private class CaptureTrustManager : X509TrustManager {
        @Volatile var leaf: X509Certificate? = null

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
            leaf = chain.firstOrNull()
            throw CertificateException("Probe only: certificate captured, handshake refused.")
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String): Nothing =
            throw CertificateException("Not supported.")

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private companion object {
        const val CONNECT_TIMEOUT_S = 10L
        const val IO_TIMEOUT_S = 30L

        val TLS_SPEC: ConnectionSpec = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
            .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
            .build()

        /** The platform's default trust manager (system CAs only; user-installed CAs are excluded by targetSdk >= 24). */
        fun platformTrustManager(): X509TrustManager {
            val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            factory.init(null as KeyStore?)
            return factory.trustManagers.filterIsInstance<X509TrustManager>().first()
        }
    }
}
