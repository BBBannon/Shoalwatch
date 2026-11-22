package app.shoalwatch.core

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
        SERVER_ERROR, SERVER_MISMATCH, NOT_SIGNED_IN,
        /** "Sign in again" for one profile came back as a different account: it was not made active (Quick Connect: whoever approves decides). */
        WRONG_ACCOUNT,
        /** The sign-in route turned unencrypted after the user last looked: nothing was sent (password sign-in / Quick Connect). */
        SECURE_CONNECTION_LOST, SECURE_CONNECTION_LOST_QUICK_CONNECT,
        /** The network changed while Quick Connect was waiting: it stopped before sending anything more. */
        CONNECTION_CHANGED,
        /** No address of the server is verified right now: nothing was sent. */
        OFFLINE,
        /** The server answered PlaybackInfo with `ErrorCode` NotAllowed / NoCompatibleStream / RateLimitExceeded. */
        PLAYBACK_NOT_ALLOWED, NO_COMPATIBLE_STREAM, TOO_MANY_STREAMS,
        /** The player stopped on an error (mapped from Media3's error code, never its message). */
        PLAYBACK_FAILED,
        UNKNOWN,
    }

    companion object {
        fun of(kind: Kind, cause: Throwable? = null): AppError = AppError(kind, messageFor(kind), cause)

        fun from(t: Throwable): AppError {
            findAppError(t)?.let { return it }
            val kind = when {
                t is SocketTimeoutException -> Kind.TIMEOUT
                hasPinMismatch(t) -> Kind.TLS_PIN_CHANGED
                isTlsProblem(t) -> Kind.TLS_UNTRUSTED
                t is IOException -> Kind.NETWORK
                else -> Kind.UNKNOWN
            }
            return of(kind, t)
        }

        /**
         * The one mapping of a non-success HTTP status, used by the sign-in gateway and `ServerApi` alike.
         * No client follows redirects, so a 3xx means this address is not serving the API (`SERVER_NOT_FOUND`); the
         * exception is 304, which only answers a conditional request and we never send one, so it is a server fault.
         * Every other 4xx and every 5xx is `SERVER_ERROR`.
         */
        fun fromHttpStatus(code: Int): AppError = of(
            when (code) {
                401 -> Kind.UNAUTHORISED
                403 -> Kind.FORBIDDEN
                404 -> Kind.SERVER_NOT_FOUND
                304 -> Kind.SERVER_ERROR
                in 300..399 -> Kind.SERVER_NOT_FOUND
                in 400..599 -> Kind.SERVER_ERROR
                else -> Kind.UNKNOWN
            },
        )

        /** An [AppError] anywhere in [t]'s cause chain, e.g. one carried through OkHttp inside an IOException. */
        private fun findAppError(t: Throwable): AppError? {
            var current: Throwable? = t
            var depth = 0
            while (current != null && depth < 10) {
                if (current is AppError) return current
                current = current.cause
                depth++
            }
            return null
        }

        /** Looks through causes and suppressed exceptions, in case a TLS provider re-wraps the failure. */
        private fun hasPinMismatch(t: Throwable, depth: Int = 0): Boolean {
            if (t is PinMismatchException) return true
            if (depth >= 10) return false
            return listOfNotNull(t.cause).plus(t.suppressed).any { hasPinMismatch(it, depth + 1) }
        }

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
            Kind.TLS_PIN_CHANGED -> "This server's security certificate has changed. Don't continue unless you expected this."
            Kind.CLEARTEXT_BLOCKED -> "Unencrypted connections to this server are blocked."
            Kind.CLIENT_CERT_REQUIRED -> "This server needs a security certificate from your phone. Choose one in the server's settings."
            Kind.SERVER_NOT_FOUND -> "No Jellyfin server was found at that address."
            Kind.SERVER_UNSUPPORTED -> "This server's version isn't supported."
            Kind.UNAUTHORISED -> "Your sign-in was not accepted. Please sign in again."
            Kind.QUICK_CONNECT_DISABLED -> "Quick Connect is switched off on this server."
            Kind.FORBIDDEN -> "You don't have permission to do that."
            Kind.SERVER_ERROR -> "The server reported a problem. Please try again later."
            Kind.SERVER_MISMATCH -> "That address belongs to a different server. Check the address and try again."
            Kind.NOT_SIGNED_IN -> "You're signed out. Sign in again to continue."
            Kind.WRONG_ACCOUNT ->
                "That signed in as a different account, so nothing changed. To sign in again as this profile, approve the code while signed in as it, or use its password."
            Kind.SECURE_CONNECTION_LOST ->
                "The secure connection isn't available right now. Check the notice above, then press Sign in again to continue."
            Kind.SECURE_CONNECTION_LOST_QUICK_CONNECT ->
                "The secure connection isn't available right now. Check the notice above, then press Try again to continue."
            Kind.CONNECTION_CHANGED -> "Your connection changed. Start Quick Connect again."
            Kind.OFFLINE -> "You're offline. Showing what's saved on this phone where possible."
            Kind.PLAYBACK_NOT_ALLOWED -> "Your account isn't allowed to play this. Ask the server's owner."
            Kind.NO_COMPATIBLE_STREAM -> "This can't be played on this phone, and the server couldn't convert it."
            Kind.TOO_MANY_STREAMS -> "Too many people are watching on this server right now. Try again later."
            Kind.PLAYBACK_FAILED -> "Playback stopped because of a problem with this video."
            Kind.UNKNOWN -> "Something went wrong. Please try again."
        }
    }
}
