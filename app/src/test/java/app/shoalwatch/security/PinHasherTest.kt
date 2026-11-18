package app.shoalwatch.security

import app.shoalwatch.core.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import javax.crypto.KeyGenerator

class PinHasherTest {
    private fun aesKey() = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val key = aesKey()
    private val box = SecretBox { key }
    private val hasher = PinHasher(box, iterations = 1_000)

    @Test fun theSamePinMatchesAndNoOtherDoes() {
        val sealed = hasher.seal("4826".toCharArray())
        assertTrue(hasher.matches("4826".toCharArray(), sealed))
        assertFalse(hasher.matches("4827".toCharArray(), sealed))
        assertFalse("one digit longer", hasher.matches("48260".toCharArray(), sealed))
        assertFalse(hasher.matches("482".toCharArray(), sealed))
    }

    @Test fun twoSealsOfTheSamePinDiffer() {
        val a = hasher.seal("4826".toCharArray())
        val b = hasher.seal("4826".toCharArray())
        assertFalse(a.contentEquals(b))
        assertFalse("the salt differs, not only the IV", box.open(a) == box.open(b))
    }

    @Test fun theOpenedTextIsTheVersionedFormatWithoutThePin() {
        val pin = "73950281"
        val text = box.open(hasher.seal(pin.toCharArray()))
        assertTrue(text, text.startsWith("p1:1000:"))
        val parts = text.split(':')
        assertEquals(4, parts.size)
        assertEquals(16, java.util.Base64.getDecoder().decode(parts[2]).size)
        assertEquals(32, java.util.Base64.getDecoder().decode(parts[3]).size)
        assertFalse("no digit run equal to the PIN", Regex("[0-9]+").findAll(text).any { it.value.contains(pin) })
    }

    @Test fun aBlobSealedUnderAnotherKeyThrowsAppError() {
        val other = PinHasher(SecretBox { aesKey() }, iterations = 1_000).seal("4826".toCharArray())
        try {
            hasher.matches("4826".toCharArray(), other)
            fail("expected AppError")
        } catch (e: AppError) {
            assertEquals(AppError.Kind.UNKNOWN, e.kind)
        }
    }

    @Test fun aSealedTextInTheWrongFormatThrowsAppErrorNamingNothing() {
        for (bad in listOf("", "p2:1000:AAAA:AAAA", "p1:0:AAAA:AAAA", "p1:x:AAAA:AAAA", "p1:1000:@@@:AAAA")) {
            try {
                hasher.matches("4826".toCharArray(), box.seal(bad))
                fail("expected AppError for \"$bad\"")
            } catch (e: AppError) {
                assertEquals(AppError.Kind.UNKNOWN, e.kind)
            }
        }
    }

    @Test fun thePinArraysAreNotChangedByTheHasher() {
        // The caller (ProfileLocks) zeroes them; the hasher only reads.
        val pin = "4826".toCharArray()
        val sealed = hasher.seal(pin)
        assertEquals("4826", String(pin))
        assertTrue(hasher.matches(pin, sealed))
        assertEquals("4826", String(pin))
    }

    @Test fun theDefaultIsOneHundredThousandIterations() {
        assertEquals(100_000, PinHasher.ITERATIONS)
        assertTrue(box.open(PinHasher(box).seal("4826".toCharArray())).startsWith("p1:100000:"))
    }

    @Test fun matchesComparesInConstantTime() {
        val source = File("src/main/java/app/shoalwatch/security/PinHasher.kt")
        assertTrue(source.absolutePath, source.isFile)
        val text = source.readText()
        assertTrue(text.contains("MessageDigest.isEqual"))
        assertFalse(text.contains("contentEquals"))
    }
}
