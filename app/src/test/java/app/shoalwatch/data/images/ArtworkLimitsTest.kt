package app.shoalwatch.data.images

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.data.images.ArtworkLimits.Kind
import app.shoalwatch.data.servers.Session
import app.shoalwatch.testId
import coil3.size.Dimension
import coil3.size.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkLimitsTest {
    private val item = testId("item")

    @Test fun bucketIsTheSmallestOneAtLeastTheRequestedWidth() {
        assertEquals(160, ArtworkLimits.bucketFor(1))
        assertEquals(160, ArtworkLimits.bucketFor(160))
        assertEquals(320, ArtworkLimits.bucketFor(161))
        assertEquals(320, ArtworkLimits.bucketFor(320))
        assertEquals(480, ArtworkLimits.bucketFor(400))
        assertEquals(720, ArtworkLimits.bucketFor(481))
        assertEquals(1280, ArtworkLimits.bucketFor(721))
        assertEquals(1280, ArtworkLimits.bucketFor(1280))
    }

    @Test fun hugeAndNonsenseWidthsAreClampedToTheLargestBucket() {
        assertEquals(1280, ArtworkLimits.bucketFor(1281))
        assertEquals(1280, ArtworkLimits.bucketFor(Int.MAX_VALUE))
        assertEquals(160, ArtworkLimits.bucketFor(0))
        assertEquals(160, ArtworkLimits.bucketFor(-5))
    }

    @Test fun anUndefinedWidthUsesTheDefaultBucket() {
        assertEquals(480, ArtworkLimits.bucketFor(null))
        assertEquals(480, ArtworkLimits.bucketFor(Size(Dimension.Undefined, Dimension.Undefined)))
        assertEquals(480, ArtworkLimits.bucketFor(Size.ORIGINAL))
        assertEquals(320, ArtworkLimits.bucketFor(Size(300, 450)))
        assertEquals(1280, ArtworkLimits.bucketFor(Size(Dimension.Pixels(5000), Dimension.Undefined)))
    }

    @Test fun contentTypeIsCaseInsensitiveParametersIgnoredAndOnlyThreeAreAllowed() {
        assertEquals(Kind.JPEG, ArtworkLimits.kindOfContentType("image/jpeg"))
        assertEquals(Kind.PNG, ArtworkLimits.kindOfContentType("IMAGE/PNG"))
        assertEquals(Kind.WEBP, ArtworkLimits.kindOfContentType("image/webp; charset=binary"))
        assertEquals(Kind.JPEG, ArtworkLimits.kindOfContentType("  Image/Jpeg ;q=1"))
        for (bad in listOf("image/svg+xml", "image/gif", "image/*", "image/jpg", "image/avif", "text/html", "application/octet-stream", "image/jpegx", "", " ", null)) {
            assertNull("$bad", ArtworkLimits.kindOfContentType(bad))
        }
    }

    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
    private val iend = byteArrayOf(0, 0, 0, 0, 'I'.code.toByte(), 'E'.code.toByte(), 'N'.code.toByte(), 'D'.code.toByte(), 0xAE.toByte(), 0x42, 0x60, 0x82.toByte())
    private fun webp(declared: Int, total: Int): ByteArray =
        ByteArray(total).also {
            "RIFF".forEachIndexed { i, c -> it[i] = c.code.toByte() }
            for (i in 0..3) it[4 + i] = (declared shr (8 * i)).toByte()
            "WEBPVP8 ".forEachIndexed { i, c -> it[8 + i] = c.code.toByte() }
        }

    @Test fun magicBytesIdentifyTheThreeAllowedFormatsOnly() {
        assertEquals(Kind.JPEG, ArtworkLimits.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)))
        assertEquals(Kind.PNG, ArtworkLimits.sniff(png + ByteArray(8)))
        assertEquals(Kind.WEBP, ArtworkLimits.sniff(webp(100, 120)))
        assertNull(ArtworkLimits.sniff("GIF89a......".toByteArray()))
        assertNull(ArtworkLimits.sniff("<svg xmlns=...".toByteArray()))
        assertNull(ArtworkLimits.sniff("<!DOCTYPE html>".toByteArray()))
        assertNull(ArtworkLimits.sniff("RIFF....WAVEfmt ".toByteArray()))
        assertNull(ArtworkLimits.sniff(ByteArray(0)))
        assertNull(ArtworkLimits.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte())))
        assertNull(ArtworkLimits.sniff(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00, 0xE0.toByte())))
    }

    @Test fun truncatedImagesAreNotComplete() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        assertTrue(ArtworkLimits.isComplete(Kind.JPEG, jpeg))
        assertFalse(ArtworkLimits.isComplete(Kind.JPEG, jpeg.copyOf(jpeg.size - 2)))
        assertTrue(ArtworkLimits.isComplete(Kind.PNG, png + iend))
        assertFalse(ArtworkLimits.isComplete(Kind.PNG, png + ByteArray(30)))
        assertFalse(ArtworkLimits.isComplete(Kind.PNG, (png + iend).copyOf(png.size + 6)))
        assertTrue(ArtworkLimits.isComplete(Kind.WEBP, webp(100, 108)))
        assertFalse("RIFF length says more than arrived", ArtworkLimits.isComplete(Kind.WEBP, webp(500, 108)))
        assertFalse("RIFF length says less: trailing junk", ArtworkLimits.isComplete(Kind.WEBP, webp(40, 108)))
    }

    @Test fun declaredDimensionsAreBoundedPerSideAndInTotal() {
        assertTrue(ArtworkLimits.dimensionsAcceptable(1280, 1920))
        assertTrue(ArtworkLimits.dimensionsAcceptable(8192, 4880))
        assertTrue(ArtworkLimits.dimensionsAcceptable(1, 1))
        assertFalse("side over 8192", ArtworkLimits.dimensionsAcceptable(8193, 10))
        assertFalse("side over 8192", ArtworkLimits.dimensionsAcceptable(10, 8193))
        assertFalse("over 40 megapixels", ArtworkLimits.dimensionsAcceptable(8192, 4883))
        assertFalse(ArtworkLimits.dimensionsAcceptable(20000, 20000))
        assertFalse(ArtworkLimits.dimensionsAcceptable(0, 100))
        assertFalse(ArtworkLimits.dimensionsAcceptable(100, -1))
    }

    @Test fun cacheKeyIsBuiltFromTheRefAndBucketNeverAnAddressOrUser() {
        val ref = ArtworkRef.of(Session(S1, U1), item, ArtworkType.BACKDROP, "abc123")!!
        val key = ArtworkLimits.cacheKey(ref, 720)
        assertEquals("art:$S1:$item:BACKDROP:abc123:720", key)
        assertFalse(key.contains(U1))
        assertEquals("a different user of the same server shares the entry", key, ArtworkLimits.cacheKey(ArtworkRef.of(Session(S1, U2), item, ArtworkType.BACKDROP, "abc123")!!, 720))
        assertTrue("a new tag is a new entry", key != ArtworkLimits.cacheKey(ArtworkRef.of(Session(S1, U1), item, ArtworkType.BACKDROP, "abc124")!!, 720))
        assertTrue("a new bucket is a new entry", key != ArtworkLimits.cacheKey(ref, 480))
        assertNotNull(ref)
    }
}
