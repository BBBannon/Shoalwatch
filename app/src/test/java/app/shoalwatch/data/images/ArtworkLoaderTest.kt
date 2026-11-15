package app.shoalwatch.data.images

import android.net.Uri
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer
import okio.FileSystem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The loader is a single-purpose tool: only an [ArtworkRef] can be loaded, nothing else can reach a fetcher. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ArtworkLoaderTest : ArtworkTestBase() {
    private fun assertRefused(model: Any) {
        val error = failed(model)
        assertEquals("nothing was fetched for ${model.javaClass.simpleName}", 0, web.requestCount)
        assertTrue(error is ModelNotAllowedException)
        assertFalse((error.message ?: "").contains("example.org"))
    }

    @Test fun aPlainHttpsStringIsRefusedAndNothingIsRequested() {
        assertRefused("https://example.org/x.jpg")
        assertRefused("http://127.0.0.1:${web.port}/x.jpg")
    }

    @Test fun aUriAnHttpUrlAndAFileAreRefused() {
        assertRefused(Uri.parse("https://example.org/x.jpg"))
        assertRefused("https://example.org/x.jpg".toHttpUrl())
        val file = tmp.newFile("pic.jpg").apply { writeBytes(bitmapBytes()) }
        assertRefused(file)
        assertRefused(Uri.fromFile(file))
        assertRefused(file.absolutePath)
        assertRefused("file://${file.absolutePath}")
        assertRefused("content://media/external/images/1")
        assertRefused("android.resource://app.shoalwatch/1")
        assertRefused("file:///android_asset/x.png")
        assertRefused("data:image/png;base64,AAAA")
        assertRefused(bitmapBytes())
    }

    @Test fun aRefStillLoadsThroughTheSameLoader() {
        web.enqueue(image(bitmapBytes(), "image/jpeg"))
        assertEquals(40, loaded(ref()).width)
    }

    @Test fun theLoaderHasNoNetworkOrAnimatedComponentsOfItsOwn() {
        val names = (loader.components.fetcherFactories.map { it.first.javaClass.name } + loader.components.decoderFactories.map { it.javaClass.name }).joinToString()
        for (banned in listOf("Network", "Http", "Gif", "Animated", "Svg", "Video")) assertFalse("$banned in $names", names.contains(banned, ignoreCase = true))
        assertTrue(names.contains("ArtworkFetcher"))
        assertTrue(loader.components.interceptors.first().javaClass.name.contains("ArtworkOnly"))
        assertEquals("our own decoder is consulted first", ArtworkDecoder.Factory::class.java, loader.components.decoderFactories.first().javaClass)
    }

    @Test fun theDiskCacheLivesInTheCacheDirectoryAndIsCappedAt250Mb() {
        assertEquals(250L * 1024 * 1024, loader.diskCache!!.maxSize)
        val built = ArtworkLoader.build(context, api)
        try {
            assertEquals(File(context.cacheDir, "artwork").absolutePath, built.diskCache!!.directory.toString().replace('\\', '/').let { File(it).absolutePath })
        } finally { built.shutdown() }
    }

    @Test fun theDecoderRefusesAnAbsurdImageEvenIfItReachedTheCache() {
        val source = SourceFetchResult(ImageSource(Buffer().write(pngDeclaring(20000, 20000)), FileSystem.SYSTEM), "image/png", DataSource.DISK)
        val decoder = ArtworkDecoder.Factory().create(source, Options(context), loader)
        assertNotNull(decoder)
        try {
            runBlocking { decoder!!.decode() }
            fail("expected a refusal")
        } catch (e: ArtworkRejectedException) {
            assertEquals(ArtworkRejectedException.Reason.DIMENSIONS, e.reason)
        }
    }

    @Test fun theDecoderDownsamplesSoTheBitmapNeverExceedsTwiceTheBucket() {
        val bytes = bitmapBytes(3000, 2000, android.graphics.Bitmap.CompressFormat.PNG)
        val source = SourceFetchResult(ImageSource(Buffer().write(bytes), FileSystem.SYSTEM), "image/png", DataSource.DISK)
        val options = Options(context, size = coil3.size.Size(5000, 5000))
        val result = runBlocking { ArtworkDecoder.Factory().create(source, options, loader)!!.decode()!! }
        assertTrue(result.isSampled)
        assertTrue("${result.image.width} wide", result.image.width <= 2 * 1280)
    }
}
