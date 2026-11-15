package app.shoalwatch.data.playback.engine

import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.data.playback.MediaPaths
import app.shoalwatch.data.playback.PlaybackFakes
import app.shoalwatch.ui.browse.BEN
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
    private fun item() = EngineItem(PlaybackFakes.route(BEN), MediaPaths.directStream(PlaybackFakes.ITEM, PlaybackFakes.SOURCE, null), PlaybackFakes.ITEM)

    @Test fun twoEnginesAliveAtOnceBothLoadWithoutAClash() {
        val first = ExoPlayerEngine(context)
        val second = ExoPlayerEngine(context)
        first.load(item(), 0, playWhenReady = false)
        second.load(item(), 0, playWhenReady = false)
        first.release()
        second.release()
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
}
