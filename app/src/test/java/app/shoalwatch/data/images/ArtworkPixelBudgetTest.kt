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
import org.junit.Assert.fail
import org.junit.Test
import org.robolectric.annotation.GraphicsMode

/** A hostile but "valid" image can never decode to more than 4,000,000 pixels, whatever the size asked for, nor wider than twice the bucket. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkPixelBudgetTest : ArtworkTestBase() {
    private val budget = 4_000_000L

    private fun decode(bytes: ByteArray, requestedWidth: Int): DecodeResult {
        val source = SourceFetchResult(ImageSource(Buffer().write(bytes), FileSystem.SYSTEM), "image/png", DataSource.DISK)
        return runBlocking { ArtworkDecoder.Factory().create(source, Options(context, size = Size(requestedWidth, requestedWidth * 2)), loader).decode()!! }
    }

    private fun assertWithinBudget(label: String, result: DecodeResult, bucket: Int) {
        val w = result.image.width
        val h = result.image.height
        assertTrue("$label decoded to ${w}x$h = ${w.toLong() * h} px", w.toLong() * h <= budget)
        assertTrue("$label is $w wide for the $bucket bucket", w <= 2 * bucket)
    }

    @Test fun anImageTwoThousandFiveHundredBySeveralThousandHighDecodesWithinTheBudget() {
        assertWithinBudget("2559x8192", decode(pngSolid(2559, 8192), 1280), 1280)
    }

    @Test fun theSameImageOnItsSideDecodesWithinTheBudget() {
        assertWithinBudget("8192x2559", decode(pngSolid(8192, 2559), 1280), 1280)
    }

    @Test fun anImageOverFortyMegapixelsIsRefusedNotDecoded() {
        val bytes = pngSolid(8192, 8192)
        val source = SourceFetchResult(ImageSource(Buffer().write(bytes), FileSystem.SYSTEM), "image/png", DataSource.DISK)
        try {
            runBlocking { ArtworkDecoder.Factory().create(source, Options(context, size = Size(1280, 1280)), loader).decode() }
            fail("expected a refusal")
        } catch (e: ArtworkRejectedException) {
            assertEquals(ArtworkRejectedException.Reason.DIMENSIONS, e.reason)
        }
    }

    @Test fun aNormalPosterDecodesAtEveryBucketWithinTheBudgetAndWidthRule() {
        val poster = bitmapBytes(1000, 1500, android.graphics.Bitmap.CompressFormat.PNG)
        for ((requested, bucket) in listOf(100 to 160, 300 to 320, 400 to 480, 700 to 720, 1200 to 1280)) {
            val result = decode(poster, requested)
            assertWithinBudget("1000x1500 for $bucket", result, bucket)
            assertTrue("never smaller than needed: ${result.image.width} for $bucket", result.image.width >= minOf(bucket, 1000) / 2)
        }
        assertEquals("a poster at the biggest bucket is not downsampled", 1000, decode(poster, 1280).image.width)
    }

    @Test fun aOnePixelWideStripIsKeptWhole() {
        val result = decode(pngSolid(1, 8192), 160)
        assertEquals(1, result.image.width)
        assertEquals(8192, result.image.height)
    }

    @Test fun theDecodeOfAnOrdinaryPosterIsStillCappedAtTheBucketWidthTimesTwo() {
        assertWithinBudget("3000x2000", decode(bitmapBytes(3000, 2000, android.graphics.Bitmap.CompressFormat.PNG), 5000), 1280)
    }
}
