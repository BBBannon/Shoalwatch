package app.shoalwatch.data.playback.engine

import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.data.playback.MediaPaths
import app.shoalwatch.data.playback.PlaybackFakes
import app.shoalwatch.ui.browse.BEN
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The real engine, as far as Robolectric can take it (Task 6 review I-1): two players alive at once must not clash over the
 * media session. Playback itself is covered on a device by the player smoke test.
 */
@RunWith(RobolectricTestRunner::class)
class ExoPlayerEngineTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun item() = EngineItem(PlaybackFakes.route(BEN), MediaPaths.directStream(PlaybackFakes.ITEM, PlaybackFakes.SOURCE, null))

    @Test fun twoEnginesAliveAtOnceBothLoadWithoutAClash() {
        val first = ExoPlayerEngine(context)
        val second = ExoPlayerEngine(context)
        first.load(item(), 0, playWhenReady = false)
        second.load(item(), 0, playWhenReady = false)
        first.release()
        second.release()
    }

    @Test fun theMediaSessionLoadsNoArtwork() {
        val loader = ExoPlayerEngine.NoArtwork
        assertFalse(loader.supportsMimeType("image/png"))
        assertTrue(runCatching { loader.loadBitmap(android.net.Uri.parse("https://evil.example/a.png")).get() }.isFailure)
        assertTrue(runCatching { loader.decodeBitmap(ByteArray(4)).get() }.isFailure)
        assertNull(loader.loadBitmapFromMetadata(androidx.media3.common.MediaMetadata.Builder().setArtworkUri(android.net.Uri.parse("https://evil.example/b.png")).build()))
    }

    @Test fun aNewEngineAfterAReleasedOneLoadsAndReleaseIsSafeTwice() {
        val first = ExoPlayerEngine(context)
        first.load(item(), 0, playWhenReady = false)
        first.release()
        first.release()
        val second = ExoPlayerEngine(context)
        second.load(item(), 0, playWhenReady = false)
        second.load(item(), 5_000, playWhenReady = false)
        second.release()
    }

    @Test fun theVideoSurfaceIsTakenAndLetGoAndIsSafeAfterRelease() {
        val engine = ExoPlayerEngine(context)
        val view = android.view.SurfaceView(context)
        engine.video.attach(view.holder)
        assertTrue(engine.video.attached)
        engine.video.detach(android.view.SurfaceView(context).holder) // another view's holder: kept
        assertTrue(engine.video.attached)
        engine.video.detach(view.holder)
        assertFalse(engine.video.attached)
        engine.video.attach(view.holder)
        engine.release()
        assertFalse("released: the surface is let go", engine.video.attached)
        engine.video.attach(view.holder) // ignored, does not throw
        engine.video.detach(view.holder)
        assertFalse(engine.video.attached)
    }

    @Test fun theAspectRatioIsWidthOverHeightWithThePixelShape() {
        assertEquals(16f / 9f, MediaItems.aspectOf(1920, 1080, 1f)!!, 0.0001f)
        assertEquals(16f / 9f, MediaItems.aspectOf(1440, 1080, 4f / 3f)!!, 0.0001f)
        assertNull(MediaItems.aspectOf(0, 0, 1f))
        assertNull(MediaItems.aspectOf(1920, 0, 1f))
        assertNull(MediaItems.aspectOf(1920, 1080, 0f))
        assertNull(ExoPlayerEngine(context).also { it.release() }.video.aspectRatio.value)
    }
}
