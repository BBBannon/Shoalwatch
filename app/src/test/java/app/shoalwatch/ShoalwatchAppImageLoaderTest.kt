package app.shoalwatch

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.data.images.ModelNotAllowedException
import coil3.SingletonImageLoader
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The REAL application class hands Coil the guarded loader: if that wiring were lost, Coil would build a default loader with file and content fetchers. */
@RunWith(RobolectricTestRunner::class)
class ShoalwatchAppImageLoaderTest {
    private val app: ShoalwatchApp get() = ApplicationProvider.getApplicationContext()

    @Test fun theSingletonLoaderIsTheGraphsGuardedLoader() {
        assertSame(app.graph.imageLoader, SingletonImageLoader.get(app))
    }

    @Test fun theSingletonRefusesStringUriHttpUrlAndFileModelsAndSendsNothing() {
        val server = MockWebServer().apply { start() }
        try {
            val file = File.createTempFile("pic", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)); deleteOnExit() }
            val models = listOf<Any>(
                server.url("/x.jpg").toString(), Uri.parse(server.url("/x.jpg").toString()), server.url("/x.jpg"),
                "https://example.org/x.jpg".toHttpUrl(), file, Uri.fromFile(file), "file://${file.absolutePath}",
            )
            for (model in models) {
                val result = runBlocking { SingletonImageLoader.get(app).execute(ImageRequest.Builder(app).data(model).build()) }
                assertTrue("${model.javaClass.simpleName} was not refused", result is ErrorResult)
                assertTrue((result as ErrorResult).throwable is ModelNotAllowedException)
            }
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }
}
