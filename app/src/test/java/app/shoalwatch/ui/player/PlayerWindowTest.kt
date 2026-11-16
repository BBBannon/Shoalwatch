package app.shoalwatch.ui.player

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The player's window across a configuration change (Task 8 review I-1): the switch to landscape recreates the activity, and
 * that must not count as leaving the player (no pause, no orientation reset); leaving for real puts everything back.
 *
 * Driven by the Compose test rule, whose own clock applies a composition change (the player leaving) on [rule]'s
 * `waitForIdle`. Not by Compose's process-wide main-thread dispatcher: an earlier Robolectric test in the same sandbox can leave
 * that dispatcher waiting for a message the per-test looper reset threw away, after which it never runs a recomposition again
 * and the player's `onDispose` never ran (the old `idle()` failures, "expected:<-1> but was:<6>").
 */
@RunWith(RobolectricTestRunner::class)
class PlayerWindowTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private var stops = 0
    private val showing = mutableStateOf(true)

    private fun showPlayer() {
        rule.setContent {
            if (showing.value) {
                PlayerWindow(keepOn = true, locked = false)
                HostLifecycle(onStopped = { stops++ }, onStarted = {})
            }
        }
        rule.waitForIdle()
    }

    @Test fun aConfigurationChangeIsNotLeavingThePlayer() {
        showPlayer()
        val first = rule.activity
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, first.requestedOrientation)
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        assertEquals("a relaunch does not pause playback", 0, stops)
        assertEquals("nor reset the orientation it is relaunching for", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, first.requestedOrientation)
    }

    @Test fun leavingThePlayerPutsTheOrientationBackAndStoppingTheAppPauses() {
        showPlayer()
        val activity = rule.activity
        rule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        rule.waitForIdle()
        assertEquals("the app going to the background pauses", 1, stops)
        rule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        // Leaving the player: it leaves the composition, as navigating away from it does.
        rule.runOnIdle { showing.value = false }
        rule.waitForIdle()
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED, activity.requestedOrientation)
    }
}
