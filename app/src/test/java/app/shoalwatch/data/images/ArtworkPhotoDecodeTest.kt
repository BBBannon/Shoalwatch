package app.shoalwatch.data.images

import coil3.decode.DataSource
import coil3.decode.DecodeResult
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Size
import kotlinx.coroutines.runBlocking
import okio.Buffer
import okio.FileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.GraphicsMode

/**
 * Milestone 11 Task 10: a photo decodes against its own size bucket (720, 1280 or 1920), so a 1,920 px picture is sharp, while the
 * 4-megapixel cap and "never more than twice the bucket wide" still hold. Every other kind of picture is unchanged (cap 1,280).
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkPhotoDecodeTest : ArtworkTestBase() {
    private fun decode(bytes: ByteArray, type: ArtworkType, side: Int): DecodeResult {
        val source = SourceFetchResult(ImageSource(Buffer().write(bytes), FileSystem.SYSTEM, ArtworkSourceInfo(type) { }), "image/png", DataSource.DISK)
        return runBlocking { ArtworkDecoder.Factory().create(source, Options(context, size = Size(side, side)), loader).decode()!! }
    }

    @Test fun thePhotoBucketIsTheSmallestOf720And1280And1920ThatCoversTheLongerSide() {
        assertEquals(720, ArtworkLimits.photoBucket(1))
        assertEquals(720, ArtworkLimits.photoBucket(720))
        assertEquals(1280, ArtworkLimits.photoBucket(721))
        assertEquals(1280, ArtworkLimits.photoBucket(1280))
        assertEquals(1920, ArtworkLimits.photoBucket(1281))
        assertEquals(1920, ArtworkLimits.photoBucket(1920))
        assertEquals(1920, ArtworkLimits.photoBucket(3000))
    }

    @Test fun aPhotoSlotUsesThePhotoBucketsAndTheLongerSide() {
        assertEquals(1920, ArtworkLimits.bucketFor(ArtworkType.PHOTO, Size(1080, 2400)))
        assertEquals(1280, ArtworkLimits.bucketFor(ArtworkType.PHOTO, Size(800, 1200)))
        assertEquals(1280, ArtworkLimits.bucketFor(ArtworkType.PRIMARY, Size(1920, 1920)))
    }

    @Test fun theSampleSizeForAPhotoAt1920IsWhatTheCapAllows() {
        assertEquals(1, ArtworkLimits.sampleSize(2800, 1400, 1920))
        assertEquals(2, ArtworkLimits.sampleSize(4000, 3000, 1920))
        assertEquals(2, ArtworkLimits.sampleSize(3000, 4000, 1920))
        assertEquals(1, ArtworkLimits.sampleSize(1920, 1440, 1920))
    }

    @Test fun aPhotoOf2800WideDecodesWholeAtThe1920BucketButAPosterIsHalvedAtTheOldCap() {
        val big = pngSolid(2800, 1400) // 3.92 megapixels
        val photo = decode(big, ArtworkType.PHOTO, 1920)
        assertEquals("a photo keeps its 2,800 px", 2800, photo.image.width)
        assertEquals("anything else still stops at the 1,280 bucket", 1400, decode(big, ArtworkType.PRIMARY, 1920).image.width)
    }

    @Test fun aPhotoStillNeverDecodesToMoreThanFourMegapixelsOrTwiceTheBucket() {
        for ((w, h) in listOf(3500 to 2500, 2500 to 3500, 8192 to 4800, 4800 to 8192)) {
            val r = decode(pngSolid(w, h), ArtworkType.PHOTO, 1920)
            assertTrue("${w}x$h decoded to ${r.image.width}x${r.image.height}", r.image.width.toLong() * r.image.height <= 4_000_000L)
            assertTrue("${w}x$h is ${r.image.width} wide", r.image.width <= 2 * 1920)
        }
    }

    @Test fun aPhotoAtTheSmallBucketsIsUnchanged() {
        assertEquals(1200, decode(pngSolid(1200, 800), ArtworkType.PHOTO, 720).image.width)
    }
}
