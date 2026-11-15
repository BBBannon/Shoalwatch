package app.shoalwatch.data.images

import android.graphics.Bitmap
import app.shoalwatch.core.AppError
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.GraphicsMode

/** Every payload the server could send that must be refused: nothing decoded, nothing cached, an error result (a placeholder), never a crash. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkHostileInputTest : ArtworkTestBase() {
    private val jpeg get() = bitmapBytes()

    /** Serves [response], loads the ref, and checks it failed, left nothing in the disk cache and used no memory-cache entry. */
    private fun refused(response: MockResponse, widthPx: Int = 300): Throwable {
        web.enqueue(response)
        val ref = ref()
        val error = failed(ref, widthPx)
        assertNull("nothing cached", cached(ref, ArtworkLimits.bucketFor(widthPx)))
        assertEquals(0L, loader.diskCache!!.size)
        return error
    }

    @Test fun aWrongContentTypeWithRealImageBytesIsRefused() {
        refused(image(jpeg, "text/html"))
    }

    @Test fun aMissingContentTypeIsRefused() {
        refused(MockResponse().setResponseCode(200).setBody(Buffer().write(jpeg)))
    }

    @Test fun svgAndGifContentTypesAreRefusedEvenWithImageBytes() {
        refused(image(jpeg, "image/svg+xml"))
        refused(image(jpeg, "image/gif"))
        refused(image(jpeg, "image/avif"))
        refused(image(jpeg, "image/*"))
    }

    @Test fun anAllowedContentTypeWithHtmlBytesIsRefused() {
        refused(image("<html><script>alert(1)</script></html>".toByteArray(), "image/jpeg"))
    }

    @Test fun svgBytesLabelledAsAPngAreRefused() {
        refused(image("<svg xmlns='http://www.w3.org/2000/svg' width='10' height='10'/>".toByteArray(), "image/png"))
    }

    @Test fun gifBytesLabelledAsAJpegAreRefused() {
        refused(image("GIF89a\u0001\u0000\u0001\u0000\u0000\u0000\u0000;".toByteArray(Charsets.ISO_8859_1), "image/jpeg"))
    }

    @Test fun anEmptyBodyIsRefused() {
        refused(image(ByteArray(0), "image/jpeg"))
    }

    @Test fun aBodyOverEightMebibytesIsRefusedWhateverItIs() {
        val big = jpeg + ByteArray(8 * 1024 * 1024 + 10)
        val error = refused(image(big, "image/jpeg"))
        assertEquals(AppError.Kind.SERVER_ERROR, (error as AppError).kind)
    }

    @Test fun aChunkedBodyWithNoLengthIsStillCutOffAtTheLimit() {
        val big = jpeg + ByteArray(9 * 1024 * 1024)
        val response = MockResponse().setResponseCode(200).setHeader("Content-Type", "image/jpeg").setChunkedBody(Buffer().write(big), 64 * 1024)
        val error = refused(response)
        assertEquals(AppError.Kind.SERVER_ERROR, (error as AppError).kind)
    }

    @Test fun aPngDeclaringTwentyThousandPixelsASideIsRefusedBeforeAnythingIsDecoded() {
        refused(image(pngDeclaring(20000, 20000), "image/png"))
    }

    @Test fun anImageOverFortyMegapixelsOrOverASideLimitIsRefused() {
        refused(image(pngDeclaring(8192, 4900), "image/png"))
        refused(image(pngDeclaring(9000, 100), "image/png"))
        refused(image(pngDeclaring(100, 9000), "image/png"))
    }

    @Test fun aTruncatedJpegIsRefusedAndNotCached() {
        refused(image(jpeg.copyOf(jpeg.size / 2), "image/jpeg"))
    }

    @Test fun aTruncatedPngIsRefusedAndNotCached() {
        val png = bitmapBytes(30, 30, Bitmap.CompressFormat.PNG)
        refused(image(png.copyOf(png.size - 20), "image/png"))
    }

    @Test fun aBodyShorterThanItsDeclaredLengthIsRefusedAndNotCached() {
        val lying = image(jpeg, "image/jpeg").setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        web.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = lying.clone() as MockResponse }
        val ref = ref()
        failed(ref)
        assertNull(cached(ref, 320))
        assertEquals(0L, loader.diskCache!!.size)
    }

    @Test fun aRedirectIsNotFollowed() {
        val other = okhttp3.mockwebserver.MockWebServer().apply { start() }
        try {
            other.enqueue(image(jpeg, "image/jpeg"))
            val error = refused(MockResponse().setResponseCode(302).setHeader("Location", other.url("/x.jpg").toString()))
            assertEquals(AppError.Kind.SERVER_NOT_FOUND, (error as AppError).kind)
            assertEquals("nothing was fetched from the redirect target", 0, other.requestCount)
        } finally { other.shutdown() }
    }

    @Test fun aRejectedSignInIsAnErrorResultAndSignsTheAccountOutLocally() {
        val error = refused(MockResponse().setResponseCode(401))
        assertEquals(AppError.Kind.UNAUTHORISED, (error as AppError).kind)
        failed(ref("another"))
        assertEquals("the dead token is not sent again", 1, web.requestCount)
    }

    @Test fun aServerErrorAndANotFoundAreErrorResults() {
        assertEquals(AppError.Kind.SERVER_ERROR, (refused(MockResponse().setResponseCode(503)) as AppError).kind)
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, (refused(MockResponse().setResponseCode(404)) as AppError).kind)
    }

    @Test fun errorsNeverCarryTheTokenTheAddressOrTheRefsTag() {
        val text = refused(image("nope".toByteArray(), "text/plain")).let { generateSequence(it) { t -> t.cause }.joinToString(" ") { t -> "${t.javaClass.name} ${t.message}" } }
        assertFalse(text.contains("tok-1")); assertFalse(text.contains("127.0.0.1")); assertFalse(text.contains("tag1")); assertFalse(text.contains(item))
        assertTrue(text.isNotEmpty())
    }
}
