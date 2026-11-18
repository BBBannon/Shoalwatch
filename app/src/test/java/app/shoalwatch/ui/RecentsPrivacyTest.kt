package app.shoalwatch.ui

import android.app.Activity
import android.view.WindowManager
import app.shoalwatch.ui.components.SecureWindowFlag
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Hiding the recent-apps preview (Milestone 7 Task 4). Only the API 34 and 35 Robolectric images are on this PC, so the API 29–32
 * path is run through `apply`'s [sdk] parameter on API 34.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecentsPrivacyTest {
    private val original = RecentsPrivacy.recentsSwitch

    @After fun restore() {
        RecentsPrivacy.recentsSwitch = original
    }

    private fun activity(): Activity = Robolectric.buildActivity(Activity::class.java).setup().get()

    private fun Activity.secure() = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

    @Test fun onApi33AndLaterTheScreenshotSwitchIsUsedAndScreenshotsStayAllowed() {
        val calls = mutableListOf<Boolean>()
        RecentsPrivacy.recentsSwitch = { _, enabled -> calls += enabled }
        val activity = activity()
        RecentsPrivacy.apply(activity, true)
        assertFalse("FLAG_SECURE stays off", activity.secure())
        RecentsPrivacy.apply(activity, false)
        assertEquals(listOf(false, true), calls)
    }

    @Test fun theRealSwitchRunsOnApi34() {
        RecentsPrivacy.apply(activity(), true) // the framework call itself, as the app makes it
    }

    @Test fun onApi32TheSecureFlagIsHeldWhileHidden() {
        val activity = activity()
        RecentsPrivacy.apply(activity, true, sdk = 32)
        assertTrue(activity.secure())
        RecentsPrivacy.apply(activity, true, sdk = 32) // a repeat holds once
        RecentsPrivacy.apply(activity, false, sdk = 32)
        assertFalse(activity.secure())
    }

    @Test fun onApi32AnotherHolderKeepsTheFlagAfterRecentsLetsGo() {
        val activity = activity()
        RecentsPrivacy.apply(activity, true, sdk = 32)
        SecureWindowFlag.acquire(activity.window) // an admin screen opens meanwhile
        RecentsPrivacy.apply(activity, false, sdk = 32)
        assertTrue("the admin screen still needs it", activity.secure())
        SecureWindowFlag.release(activity.window)
        assertFalse(activity.secure())
    }
}
