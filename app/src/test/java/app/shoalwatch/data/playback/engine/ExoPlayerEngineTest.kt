package app.shoalwatch.data.playback.engine

import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
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

    @Test fun aMediaSessionControllerCannotStartPlaybackWhileTheHostIsStopped() {
        // Final review N-4: the session is built over [ExoPlayerEngine.sessionPlayer]; the app's own play is not affected.
        val engine = ExoPlayerEngine(context)
        engine.load(item(), 0, playWhenReady = false)
        engine.setHostStopped(true)
        engine.sessionPlayer.play()
        engine.sessionPlayer.playWhenReady = true
        assertFalse("a headset's play is refused", engine.playWhenReady)
        engine.sessionPlayer.pause()
        assertFalse(engine.playWhenReady)
        engine.setHostStopped(false)
        engine.sessionPlayer.play()
        assertTrue("allowed again once the host starts", engine.playWhenReady)
        engine.sessionPlayer.pause()
        assertFalse("pause always works", engine.playWhenReady)
        engine.setHostStopped(true)
        engine.play()
        assertTrue("the app's own play is not a controller's", engine.playWhenReady)
        engine.release()
    }

    /**
     * Final-fixes review m-3: the session's play action (`Util.handlePlayButtonAction`, which every controller's play reaches)
     * prepares an idle (failed) player and rewinds an ended one before its refused `play()`; while the host is stopped neither
     * may happen.
     */
    @OptIn(UnstableApi::class)
    @Test fun whileTheHostIsStoppedAPlayButtonNeitherPreparesAFailedPlayerNorRewindsAnEndedOne() {
        val engine = ExoPlayerEngine(context)
        engine.load(item(), 0, playWhenReady = false)
        val session = engine.sessionPlayer
        session.stop() // idle, as after a playback error
        engine.setHostStopped(true)
        Util.handlePlayButtonAction(session)
        assertEquals("not prepared again", Player.STATE_IDLE, session.playbackState)
        session.seekTo(5_000)
        session.seekToDefaultPosition()
        assertEquals("not sent back to the start", 5_000L, session.currentPosition)
        assertFalse(engine.playWhenReady)
        engine.setHostStopped(false)
        session.seekToDefaultPosition()
        assertEquals("allowed again once the host starts", 0L, session.currentPosition)
        Util.handlePlayButtonAction(session)
        assertTrue("prepared again once the host starts", session.playbackState != Player.STATE_IDLE)
        engine.release()
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
