package app.shoalwatch.ui

import app.shoalwatch.net.CertificateInfo
import app.shoalwatch.net.fingerprintForDisplay
import java.time.Instant

/** What the trust prompt shows. Subject and issuer can name the server's host, so [toString] reveals nothing. */
data class CertificateSummary(
    /** The pin that trusting stores (base64 SHA-256 of the public key). */
    val spkiSha256: String,
    /** Colon-separated upper-case hex, for display. */
    val fingerprint: String,
    val subject: String,
    val issuer: String,
    val notAfter: Instant,
) {
    override fun toString() = "CertificateSummary(<redacted>)"
}

fun CertificateInfo.toSummary() = CertificateSummary(spkiSha256, fingerprintForDisplay(), subject, issuer, notAfter)
