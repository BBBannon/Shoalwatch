package app.offlinefin.core

import java.io.IOException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * The only error type the UI sees. [userMessage] is written by us (UK English) and never
 * includes text from an underlying exception, which can contain URLs or tokens.
 */
class AppError(val kind: Kind, val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause) {
    enum class Kind {
        NETWORK, TIMEOUT, TLS_UNTRUSTED, TLS_PIN_CHANGED, CLEARTEXT_BLOCKED, CLIENT_CERT_REQUIRED,
        SERVER_NOT_FOUND, SERVER_UNSUPPORTED, UNAUTHORISED, QUICK_CONNECT_DISABLED, FORBIDDEN,
        SERVER_ERROR, NOT_SIGNED_IN, UNKNOWN,
    }

    companion object {
        fun of(kind: Kind, cause: Throwable? = null): AppError = AppError(kind, messageFor(kind), cause)

        fun from(t: Throwable): AppError {
            if (t is AppError) return t
            val kind = when {
                t is SocketTimeoutException -> Kind.TIMEOUT
                isTlsProblem(t) -> Kind.TLS_UNTRUSTED
                t is IOException -> Kind.NETWORK
                else -> Kind.UNKNOWN
            }
            return of(kind, t)
        }

        fun fromHttpStatus(code: Int): AppError = of(
            when (code) {
                401 -> Kind.UNAUTHORISED
                403 -> Kind.FORBIDDEN
                404 -> Kind.SERVER_NOT_FOUND
                in 500..599 -> Kind.SERVER_ERROR
                else -> Kind.UNKNOWN
            },
        )

        private fun isTlsProblem(t: Throwable): Boolean {
            var current: Throwable? = t
            var depth = 0
            while (current != null && depth < 10) {
                if (current is SSLPeerUnverifiedException || current is SSLHandshakeException ||
                    current is CertificateException
                ) return true
                current = current.cause
                depth++
            }
            return false
        }

        private fun messageFor(kind: Kind): String = when (kind) {
            Kind.NETWORK -> "Couldn't reach the server. Check your connection and the server address."
            Kind.TIMEOUT -> "The server took too long to respond. Please try again."
            Kind.TLS_UNTRUSTED -> "This server's security certificate isn't trusted."
            Kind.TLS_PIN_CHANGED -> "This server's security certificate has changed since you trusted it."
            Kind.CLEARTEXT_BLOCKED -> "Unencrypted connections to this server are blocked."
            Kind.CLIENT_CERT_REQUIRED -> "This server requires a client certificate, which isn't supported."
            Kind.SERVER_NOT_FOUND -> "No Jellyfin server was found at that address."
            Kind.SERVER_UNSUPPORTED -> "This server's version isn't supported."
            Kind.UNAUTHORISED -> "Your sign-in was not accepted. Please sign in again."
            Kind.QUICK_CONNECT_DISABLED -> "Quick Connect is switched off on this server."
            Kind.FORBIDDEN -> "You don't have permission to do that."
            Kind.SERVER_ERROR -> "The server reported a problem. Please try again later."
            Kind.NOT_SIGNED_IN -> "You're not signed in."
            Kind.UNKNOWN -> "Something went wrong. Please try again."
        }
    }
}
