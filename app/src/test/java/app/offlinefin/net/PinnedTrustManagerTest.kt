package app.offlinefin.net

import app.offlinefin.core.AppError
import app.offlinefin.core.PinMismatchException
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.cert.CertificateException
import java.security.cert.X509Certificate

class PinnedTrustManagerTest {
    private val selfSigned = HeldCertificate.Builder().commonName("lan").build()
    private val root = HeldCertificate.Builder().certificateAuthority(0).build()
    private val chained = HeldCertificate.Builder().signedBy(root).build()

    private val trustsNothing = HandshakeCertificates.Builder().addTrustedCertificate(HeldCertificate.Builder().certificateAuthority(0).build().certificate).build().trustManager
    private val trustsRoot = HandshakeCertificates.Builder().addTrustedCertificate(root.certificate).build().trustManager

    private fun pinOf(c: HeldCertificate) = spkiSha256Base64(c.certificate)
    private fun chain(c: HeldCertificate): Array<X509Certificate> = arrayOf(c.certificate)

    @Test fun `self-signed without a pin is rejected with the system failure`() {
        try {
            PinnedTrustManager(trustsNothing, null).checkServerTrusted(chain(selfSigned), "RSA")
            fail()
        } catch (e: CertificateException) {
            assertTrue(e !is PinMismatchException)
        }
    }

    @Test fun `self-signed with the right pin is accepted`() =
        PinnedTrustManager(trustsNothing, pinOf(selfSigned)).checkServerTrusted(chain(selfSigned), "RSA")

    @Test fun `self-signed with a wrong pin is a pin mismatch`() {
        try {
            PinnedTrustManager(trustsNothing, pinOf(chained)).checkServerTrusted(chain(selfSigned), "RSA")
            fail()
        } catch (e: PinMismatchException) {
            assertEquals(AppError.Kind.TLS_PIN_CHANGED, AppError.from(e).kind)
        }
    }

    @Test fun `system-trusted chain is accepted with no pin`() =
        PinnedTrustManager(trustsRoot, null).checkServerTrusted(arrayOf(chained.certificate, root.certificate), "RSA")

    @Test fun `system-trusted chain is accepted even when the pin does not match`() =
        PinnedTrustManager(trustsRoot, pinOf(selfSigned))
            .checkServerTrusted(arrayOf(chained.certificate, root.certificate), "RSA")

    @Test fun `empty chain is never accepted`() {
        try {
            PinnedTrustManager(trustsNothing, pinOf(selfSigned)).checkServerTrusted(emptyArray(), "RSA")
            fail()
        } catch (_: Exception) {
        }
    }

    @Test fun `client certificates are never trusted`() {
        try {
            PinnedTrustManager(trustsRoot, null).checkClientTrusted(chain(chained), "RSA")
            fail()
        } catch (_: CertificateException) {
        }
    }

    @Test fun `fingerprintForDisplay is upper-case colon-separated hex`() {
        val info = CertificateInfo("s", "i", java.time.Instant.EPOCH, pinOf(selfSigned))
        assertTrue(Regex("^([0-9A-F]{2}:){31}[0-9A-F]{2}$").matches(info.fingerprintForDisplay()))
    }
}
