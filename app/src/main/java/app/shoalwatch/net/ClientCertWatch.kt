package app.shoalwatch.net

import app.shoalwatch.core.AppError
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.Handshake
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Response
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.WeakHashMap
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509KeyManager

/**
 * Recognises a server that wants a client certificate (S5) from what the TLS layer itself does, never from
 * exception text: the key manager is asked to choose a client alias only when the server sent a
 * CertificateRequest during the handshake. One watch per client from [HttpClientFactory.clientFor].
 *
 * What is recorded, and where:
 * - the call whose handshake was asked (TLS 1.2 servers then fail the handshake itself), via an
 *   [EventListener] that marks the thread running that call's TLS handshake;
 * - the socket that was asked (TLS 1.3 servers fail the first read after the handshake; a proxy such as nginx
 *   answers 400 on that connection, also on later requests that reuse it).
 *
 * [interceptor] then turns a failure on such a call or connection into `CLIENT_CERT_REQUIRED`, but only when no
 * client certificate is configured. A server that asks optionally and then serves the request is unaffected.
 * Weak references only: nothing outlives its call or socket.
 */
internal class ClientCertWatch {
    private val handshaking = ThreadLocal<Call?>()
    private val askedCalls: MutableSet<Call> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val askedSockets: MutableSet<Socket> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    private fun asked(socket: Socket?) {
        handshaking.get()?.let { askedCalls += it }
        if (socket != null) askedSockets += socket
    }

    private fun wasAsked(call: Call, socket: Socket?) = call in askedCalls || (socket != null && socket in askedSockets)

    /** Wraps [delegate] (the KeyChain-backed manager, or null for "no certificate") and records every request for one. */
    fun keyManager(delegate: X509KeyManager?): X509ExtendedKeyManager = object : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? {
            asked(socket)
            return delegate?.chooseClientAlias(keyType, issuers, socket)
        }

        override fun chooseEngineClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, engine: SSLEngine?): String? {
            asked(null)
            return delegate?.chooseClientAlias(keyType, issuers, null)
        }

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = delegate?.getClientAliases(keyType, issuers)
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun getCertificateChain(alias: String?): Array<X509Certificate>? = delegate?.getCertificateChain(alias)
        override fun getPrivateKey(alias: String?): PrivateKey? = delegate?.getPrivateKey(alias)
    }

    /** Marks which call a TLS handshake on this thread belongs to (OkHttp runs a handshake on one thread, start to end). */
    val events: EventListener.Factory = EventListener.Factory {
        object : EventListener() {
            override fun secureConnectStart(call: Call) = handshaking.set(call)
            override fun secureConnectEnd(call: Call, handshake: Handshake?) = handshaking.remove()
            override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) =
                handshaking.remove()
        }
    }

    /**
     * For both interceptor positions. With [hasCertificate] false, a failure (or a 400/403 answer) on a call or
     * connection whose handshake asked for a certificate becomes `CLIENT_CERT_REQUIRED` (as a [BlockedRequestException]).
     */
    fun interceptor(hasCertificate: Boolean) = Interceptor { chain ->
        if (hasCertificate) return@Interceptor chain.proceed(chain.request())
        val response = try {
            chain.proceed(chain.request())
        } catch (e: BlockedRequestException) {
            throw e
        } catch (e: IOException) {
            if (wasAsked(chain.call(), chain.connection()?.socket())) throw needsCertificate()
            throw e
        }
        if ((response.code == 400 || response.code == 403) && wasAsked(chain.call(), chain.connection()?.socket())) {
            response.close()
            throw needsCertificate()
        }
        response
    }

    private fun needsCertificate() = BlockedRequestException(AppError.of(AppError.Kind.CLIENT_CERT_REQUIRED))
}
