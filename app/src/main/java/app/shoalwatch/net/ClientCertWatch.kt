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
 * Only a failure that can be blamed on the missing certificate becomes `CLIENT_CERT_REQUIRED` (and only when no
 * client certificate is configured):
 * - (a) the TLS handshake itself failed on a call whose handshake was asked for a certificate (TLS 1.2 servers
 *   alert during the handshake; the [EventListener] marks the call, `connectFailed` records the failure);
 * - (b) the FIRST response, or the first read, on a freshly handshaken connection that was asked for a certificate
 *   (none sent): a 400/403 such as nginx's "No required SSL certificate was sent", or, for TLS 1.3 (the client
 *   finishes the handshake before the server rejects it), an IOException on that first read.
 *
 * Once a connection has returned any response it is proven: nothing later on it is ever remapped, so a genuine
 * 403 from Jellyfin or a network blip behind a proxy with optional client auth keeps its own meaning.
 * Weak references only: nothing outlives its call or socket.
 */
internal class ClientCertWatch {
    private val handshaking = ThreadLocal<Call?>()
    private val askedCalls: MutableSet<Call> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val askedSockets: MutableSet<Socket> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val refusedCalls: MutableSet<Call> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))
    private val provenSockets: MutableSet<Socket> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    private fun asked(socket: Socket?) {
        handshaking.get()?.let { askedCalls += it }
        if (socket != null) askedSockets += socket
    }

    /** A fresh connection (no response yet) whose handshake was asked for a certificate. */
    private fun freshAndAsked(socket: Socket) = socket in askedSockets && socket !in provenSockets

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
            override fun connectFailed(call: Call, inetSocketAddress: InetSocketAddress, proxy: Proxy, protocol: Protocol?, ioe: IOException) {
                if (call in askedCalls) refusedCalls += call
                handshaking.remove()
            }
        }
    }

    /**
     * For both interceptor positions (only the network position knows the connection). See the class KDoc for what
     * is remapped; everything else passes through unchanged.
     */
    fun interceptor(hasCertificate: Boolean) = Interceptor { chain ->
        if (hasCertificate) return@Interceptor chain.proceed(chain.request())
        val call = chain.call()
        val socket = chain.connection()?.socket()
        val response = try {
            chain.proceed(chain.request())
        } catch (e: BlockedRequestException) {
            throw e
        } catch (e: IOException) {
            val blame = if (socket == null) call in refusedCalls else freshAndAsked(socket)
            if (blame) throw needsCertificate()
            throw e
        }
        if (socket != null) {
            if ((response.code == 400 || response.code == 403) && freshAndAsked(socket)) {
                response.close()
                throw needsCertificate()
            }
            provenSockets += socket
        }
        response
    }

    private fun needsCertificate() = BlockedRequestException(AppError.of(AppError.Kind.CLIENT_CERT_REQUIRED))
}
