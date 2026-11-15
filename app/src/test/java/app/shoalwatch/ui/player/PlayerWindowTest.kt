package app.shoalwatch.ui.player

import android.content.pm.ActivityInfo
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/**
 * The player's window across a configuration change (Task 8 review I-1): the switch to landscape recreates the activity, and
 * that must not count as leaving the player (no pause, no orientation reset); leaving for real puts everything back.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerWindowTest {
    private var stops = 0

    private fun ComponentActivity.showPlayer() = setContent {
        PlayerWindow(keepOn = true, locked = false)
        HostLifecycle(onStopped = { stops++ }, onStarted = {})
    }

    /**
     * Runs what is due AND lets a frame pass: a composition change (new content, a disposal) is applied on the next frame, which
     * a plain `idle()` only reaches when the frame happens to be due already. That made this test fail now and then under load.
     */
    private fun idle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))

    @Test fun aConfigurationChangeIsNotLeavingThePlayer() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var first: ComponentActivity
            scenario.onActivity { first = it; it.showPlayer() }
            idle()
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, first.requestedOrientation)
            scenario.recreate()
            idle()
            assertEquals("a relaunch does not pause playback", 0, stops)
            assertEquals("nor reset the orientation it is relaunching for", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, first.requestedOrientation)
        }
    }

    @Test fun leavingThePlayerPutsTheOrientationBackAndStoppingTheAppPauses() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var activity: ComponentActivity
            scenario.onActivity { activity = it; it.showPlayer() }
            idle()
            scenario.moveToState(Lifecycle.State.CREATED)
            idle()
            assertEquals("the app going to the background pauses", 1, stops)
            scenario.moveToState(Lifecycle.State.RESUMED)
            scenario.onActivity { it.setContent { } }
            idle()
            assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
        }
    }
}
