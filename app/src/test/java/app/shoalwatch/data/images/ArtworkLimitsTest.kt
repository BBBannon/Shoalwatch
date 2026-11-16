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

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val body = type.toByteArray(Charsets.US_ASCII) + data
        val crc = java.util.zip.CRC32().apply { update(body) }.value
        fun int(v: Long) = ByteArray(4) { (v shr (24 - 8 * it)).toByte() }
        return int(data.size.toLong()) + body + int(crc)
    }

    @Test fun anApngIsRecognisedByItsAnimationControlChunkBeforeTheImageData() {
        val ihdr = pngChunk("IHDR", ByteArray(13))
        val still = png + ihdr + pngChunk("IDAT", ByteArray(4)) + iend
        val apng = png + ihdr + pngChunk("acTL", ByteArray(8)) + pngChunk("IDAT", ByteArray(4)) + iend
        assertFalse(ArtworkLimits.isAnimated(Kind.PNG, still))
        assertTrue(ArtworkLimits.isAnimated(Kind.PNG, apng))
        assertFalse("a malformed chunk length never loops or throws", ArtworkLimits.isAnimated(Kind.PNG, png + byteArrayOf(0x7F, 0, 0, 0, 'a'.code.toByte())))
    }

    @Test fun anAnimatedWebpIsRecognisedByTheExtendedHeaderFlag() {
        fun vp8x(flags: Int) = webp(100, 108).also {
            "VP8X".forEachIndexed { i, c -> it[12 + i] = c.code.toByte() }
            it[20] = flags.toByte()
        }
        assertTrue(ArtworkLimits.isAnimated(Kind.WEBP, vp8x(0x02)))
        assertTrue(ArtworkLimits.isAnimated(Kind.WEBP, vp8x(0x12)))
        assertFalse(ArtworkLimits.isAnimated(Kind.WEBP, vp8x(0x10)))
        assertFalse("a plain lossy file is a still", ArtworkLimits.isAnimated(Kind.WEBP, webp(100, 108)))
        assertFalse(ArtworkLimits.isAnimated(Kind.JPEG, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
    }

    @Test fun sampleSizeKeepsTheWidthUnderTwiceTheBucketAndThePixelsUnderTheBudget() {
        assertEquals(1, ArtworkLimits.sampleSize(1000, 1500, 1280))
        assertEquals(4, ArtworkLimits.sampleSize(1000, 1500, 160))
        assertEquals(1, ArtworkLimits.sampleSize(1, 8192, 160))
        assertEquals(4, ArtworkLimits.sampleSize(2559, 8192, 1280))
        assertEquals(4, ArtworkLimits.sampleSize(8192, 2559, 1280))
        assertEquals(32, ArtworkLimits.sampleSize(8192, 100, 160))
        for (w in listOf(1, 100, 1280, 2559, 5000, 8192)) for (h in listOf(1, 800, 4880, 8192)) for (bucket in ArtworkLimits.BUCKETS.toList()) {
            if (!ArtworkLimits.dimensionsAcceptable(w, h)) continue
            val ss = ArtworkLimits.sampleSize(w, h, bucket)!!
            assertTrue("${w}x$h @ $bucket: $ss", ((w + ss - 1) / ss).toLong() * ((h + ss - 1) / ss) <= ArtworkLimits.MAX_DECODED_PIXELS)
            assertTrue("${w}x$h @ $bucket width", (w + ss - 1) / ss <= maxOf(2 * bucket, 1))
        }
    }
}
