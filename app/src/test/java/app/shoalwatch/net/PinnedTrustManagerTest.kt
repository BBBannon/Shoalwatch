package app.shoalwatch.net

import app.shoalwatch.core.AppError
import app.shoalwatch.core.PinMismatchException
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
        } catch (_: CertificateException) {
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun `a malformed pin is refused without echoing it`() {
        for (bad in listOf("not base64!!", "AAAA", "", spkiSha256Base64(selfSigned.certificate).trimEnd('='))) {
            try {
                PinnedTrustManager(trustsNothing, bad)
                fail()
            } catch (e: IllegalArgumentException) {
                assertTrue(!e.message.orEmpty().contains(bad) || bad.isEmpty())
            }
        }
    }

    @Test fun `isValidPin accepts only padded base64 of 32 bytes`() {
        assertTrue(isValidPin(pinOf(selfSigned)))
        assertTrue(!isValidPin("AAAA"))
        assertTrue(!isValidPin(pinOf(selfSigned).trimEnd('=')))
        assertTrue(!isValidPin("%%%"))
        assertTrue(!isValidPin(""))
    }

    @Test fun `CertificateInfo toString is redacted`() {
        val text = CertificateInfo("CN=secret.host", "CN=issuer.host", java.time.Instant.EPOCH, "abc").toString()
        assertEquals("CertificateInfo(<redacted>)", text)
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
