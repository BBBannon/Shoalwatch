package app.shoalwatch.net

import app.shoalwatch.core.PinMismatchException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager

/**
 * Trust decision for one server: the system's trusted authorities first; failing that, a certificate
 * whose public key is exactly the one the user pinned. With no pin, the system's failure is rethrown.
 *
 * A pinned key is accepted whatever the certificate's dates or names, because it is the user's own
 * decision about that key (typical for a self-signed LAN server). Client certificates are never trusted.
 */
class PinnedTrustManager(
    private val system: X509TrustManager,
    private val pinnedSpkiSha256: String?,
) : X509TrustManager {
    init {
        require(pinnedSpkiSha256 == null || isValidPin(pinnedSpkiSha256)) { "The pinned certificate key is not in a valid format." }
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
        try {
            system.checkServerTrusted(chain, authType)
        } catch (failure: CertificateException) {
            val pin = pinnedSpkiSha256 ?: throw failure
            val leaf = chain.firstOrNull() ?: throw failure
            if (!matchesPin(leaf, pin)) throw PinMismatchException()
        }
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String): Nothing =
        throw CertificateException("Client certificates are not accepted from servers.")

    override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers

    internal companion object {
        /** Constant-time comparison of the leaf's key hash with [pin]. */
        fun matchesPin(leaf: X509Certificate, pin: String): Boolean =
            MessageDigest.isEqual(spkiSha256Base64(leaf).toByteArray(), pin.toByteArray())
    }
}

/**
 * Hostname check for a pinned server. Normal verification ([normal], the HTTP client's own verifier) always runs first, so a certificate the
 * system trusts for a different host is still refused. Only when that fails does it accept the
 * connection, and only if the peer's leaf key is exactly the pinned one (self-signed certificates
 * for LAN addresses often carry no matching name). With no pin it is just normal verification.
 * The trust manager has already decided whether the chain itself is acceptable.
 */
class PinAwareHostnameVerifier(
    private val pinnedSpkiSha256: String?,
    private val normal: HostnameVerifier,
) : HostnameVerifier {
    override fun verify(hostname: String, session: SSLSession): Boolean {
        if (normal.verify(hostname, session)) return true
        val pin = pinnedSpkiSha256 ?: return false
        val leaf = try {
            session.peerCertificates.firstOrNull() as? X509Certificate
        } catch (_: javax.net.ssl.SSLPeerUnverifiedException) {
            null
        } ?: return false
        return PinnedTrustManager.matchesPin(leaf, pin)
    }
}
