package app.offlinefin.core

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.cert.CertificateException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppErrorTest {
    private val leak = "https://secret.host/Items?api_key=LEAKME"

    @Test fun appErrorWrappedInCauseChainIsUnwrapped() {
        val inner = AppError.of(AppError.Kind.CLEARTEXT_BLOCKED)
        assertSame(inner, AppError.from(IOException("outer", RuntimeException("mid", inner))))
    }

    @Test fun sslPeerUnverifiedIsTlsUntrusted() =
        assertEquals(AppError.Kind.TLS_UNTRUSTED, AppError.from(SSLPeerUnverifiedException(leak)).kind)

    @Test fun handshakeAndCertificateAreTlsUntrusted() {
        assertEquals(AppError.Kind.TLS_UNTRUSTED, AppError.from(SSLHandshakeException(leak)).kind)
        assertEquals(AppError.Kind.TLS_UNTRUSTED, AppError.from(CertificateException(leak)).kind)
    }

    @Test fun tlsCauseIsDetected() =
        assertEquals(AppError.Kind.TLS_UNTRUSTED, AppError.from(IOException(leak, CertificateException())).kind)

    @Test fun timeoutAndNetworkMapping() {
        assertEquals(AppError.Kind.TIMEOUT, AppError.from(SocketTimeoutException(leak)).kind)
        assertEquals(AppError.Kind.NETWORK, AppError.from(UnknownHostException(leak)).kind)
        assertEquals(AppError.Kind.NETWORK, AppError.from(ConnectException(leak)).kind)
        assertEquals(AppError.Kind.NETWORK, AppError.from(IOException(leak)).kind)
    }

    @Test fun unknownFallback() =
        assertEquals(AppError.Kind.UNKNOWN, AppError.from(IllegalStateException(leak)).kind)

    @Test fun appErrorPassesThroughUnchanged() {
        val e = AppError(AppError.Kind.FORBIDDEN, "No.")
        assertSame(e, AppError.from(e))
    }

    @Test fun httpStatusMapping() {
        assertEquals(AppError.Kind.UNAUTHORISED, AppError.fromHttpStatus(401).kind)
        assertEquals(AppError.Kind.FORBIDDEN, AppError.fromHttpStatus(403).kind)
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, AppError.fromHttpStatus(404).kind)
        assertEquals(AppError.Kind.SERVER_ERROR, AppError.fromHttpStatus(500).kind)
        assertEquals(AppError.Kind.SERVER_ERROR, AppError.fromHttpStatus(503).kind)
        assertEquals(AppError.Kind.UNKNOWN, AppError.fromHttpStatus(418).kind)
    }

    @Test fun messagesNeverLeakInputOrUrls() {
        val inputs = listOf<Throwable>(
            SSLPeerUnverifiedException(leak), SocketTimeoutException(leak), UnknownHostException(leak),
            IOException(leak), IllegalStateException(leak),
        )
        val errors = inputs.map { AppError.from(it) } + listOf(401, 403, 404, 500, 418).map { AppError.fromHttpStatus(it) }
        for (e in errors) {
            for (text in listOf(e.userMessage, e.message.orEmpty())) {
                assertFalse(text, text.contains("http", ignoreCase = true))
                assertFalse(text, text.contains("LEAKME"))
                assertFalse(text, text.contains("secret.host"))
            }
            assertEquals(e.userMessage, e.message)
            assertTrue(e.userMessage.isNotBlank())
        }
    }

    @Test fun originalThrowableKeptAsCause() {
        val t = SocketTimeoutException()
        assertSame(t, AppError.from(t).cause)
    }
}
