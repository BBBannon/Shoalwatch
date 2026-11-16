package app.shoalwatch.security

import app.shoalwatch.core.AppError
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SecretBoxTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val box = SecretBox { key }

    private fun assertRejected(sealed: ByteArray) {
        try {
            box.open(sealed)
            fail("expected AppError")
        } catch (e: AppError) {
            assertEquals(AppError.Kind.UNKNOWN, e.kind)
            assertEquals("Saved sign-in could not be read. Please sign in again.", e.message)
        }
    }

    @Test fun roundTrip() = assertEquals("tok-en ✓", box.open(box.seal("tok-en ✓")))

    @Test fun emptyStringRoundTrips() = assertEquals("", box.open(box.seal("")))

    @Test fun layoutIsVersionIvCiphertextTag() {
        val sealed = box.seal("abc")
        assertEquals(0x01.toByte(), sealed[0])
        assertEquals(1 + 12 + 3 + 16, sealed.size)
    }

    @Test fun twoSealsOfSameTextDiffer() {
        assertFalse(box.seal("same").contentEquals(box.seal("same")))
    }

    @Test fun plaintextIsNotVisibleInSealedBytes() {
        val sealed = box.seal("visibleplaintext")
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("visibleplaintext"))
    }

    @Test fun tamperingAnyByteThrowsAppError() {
        val sealed = box.seal("secret value")
        for (i in sealed.indices) {
            val copy = sealed.copyOf()
            copy[i] = (copy[i].toInt() xor 0x01).toByte()
            assertRejected(copy)
        }
    }

    @Test fun wrongVersionByteThrows() {
        val sealed = box.seal("x")
        sealed[0] = 0x02
        assertRejected(sealed)
    }

    @Test fun truncatedOrEmptyInputThrows() {
        assertRejected(ByteArray(0))
        assertRejected(byteArrayOf(0x01))
        assertRejected(box.seal("x").copyOf(10))
    }

    @Test fun sealFailureIsAppErrorWithoutLeakingCause() {
        val failing = SecretBox { throw IllegalStateException("https://secret.host/LEAKME") }
        try {
            failing.seal("x")
            fail("expected AppError")
        } catch (e: AppError) {
            assertEquals(AppError.Kind.UNKNOWN, e.kind)
            assertEquals("Sign-in could not be saved securely. Please try again.", e.message)
            assertTrue(e.cause is IllegalStateException)
        }
    }

    @Test fun wrongKeyThrows() {
        val other = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        val sealed = box.seal("x")
        try {
            SecretBox { other }.open(sealed)
            fail("expected AppError")
        } catch (e: AppError) {
            assertTrue(e.kind == AppError.Kind.UNKNOWN)
        }
    }
}
