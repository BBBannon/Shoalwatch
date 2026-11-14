package app.shoalwatch.net

import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.time.Instant
import java.util.Base64

/** What the trust-on-first-use prompt shows about a server's certificate. [spkiSha256] is standard base64 with padding. */
data class CertificateInfo(val subject: String, val issuer: String, val notAfter: Instant, val spkiSha256: String) {
    /** Subject and issuer can contain the server's hostname, so they never reach a log through string templates. */
    override fun toString() = "CertificateInfo(<redacted>)"
}

/** The SHA-256 as upper-case hex pairs separated by colons, e.g. `AB:CD:...`. */
fun CertificateInfo.fingerprintForDisplay(): String = fingerprintForDisplay(spkiSha256)

/** [fingerprintForDisplay] for a saved pin (standard base64 of the SHA-256). */
fun fingerprintForDisplay(spkiSha256: String): String =
    Base64.getDecoder().decode(spkiSha256).joinToString(":") { "%02X".format(it) }

/** Base64 (standard, padded) of SHA-256 over the certificate's SubjectPublicKeyInfo. */
fun spkiSha256Base64(cert: X509Certificate): String =
    Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded))

/** True for standard base64 with padding that decodes to exactly 32 bytes (a SHA-256 value). */
fun isValidPin(pin: String): Boolean = try {
    val bytes = Base64.getDecoder().decode(pin)
    bytes.size == 32 && Base64.getEncoder().encodeToString(bytes) == pin
} catch (_: IllegalArgumentException) {
    false
}
