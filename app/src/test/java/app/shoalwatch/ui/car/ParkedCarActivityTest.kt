package app.shoalwatch.ui.car

import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.Display
import android.view.WindowManager
import app.shoalwatch.ShoalwatchApp
import app.shoalwatch.security.Screen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Milestone 9: Android Auto blanks a `FLAG_SECURE` window on the car display, so the car activity's window is never secure: not
 * when created, not on the car screen, not after losing and regaining it. Started with a hostile intent (data and extras), as any
 * app on the phone could, it still starts. Review I1: on the phone's own display it shows only "Open Shoalwatch on your car's
 * screen", reads nothing and is not counted as the app being in front; on Android 13+ the recent-apps screen keeps no picture of it.
 * Robolectric has no car display, so the display is the activity's seam ([ParkedCarActivity.displayOf]).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h480dp-land")
class ParkedCarActivityTest {
    private val originalDisplay = ParkedCarActivity.displayOf
    private val originalRecents = ParkedCarActivity.recentsScreenshots
    private var display = CAR_DISPLAY
    private val recents = mutableListOf<Boolean>()

    init {
        ParkedCarActivity.displayOf = { display }
        ParkedCarActivity.recentsScreenshots = { _, enabled -> recents += enabled }
    }

    @After fun restore() {
        ParkedCarActivity.displayOf = originalDisplay
        ParkedCarActivity.recentsScreenshots = originalRecents
    }

    private fun ParkedCarActivity.secure() = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private val front get() = ShoalwatchApp.graph(androidx.test.core.app.ApplicationProvider.getApplicationContext()).lockParts.appFront

    private fun launch(intent: Intent? = null): ActivityController<ParkedCarActivity> =
        (if (intent != null) Robolectric.buildActivity(ParkedCarActivity::class.java, intent) else Robolectric.buildActivity(ParkedCarActivity::class.java))

    @Test fun theCarWindowIsNeverSecure() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("android-app://androidx.navigation/player/x")).putExtra("open_tab", "downloads")
        val controller = launch(intent).create()
        assertEquals("created", 0, controller.get().secure())
        controller.start().resume().visible(); idle()
        assertEquals("on the car screen", 0, controller.get().secure())
        controller.pause().resume(); idle()
        assertEquals("after losing and regaining the screen", 0, controller.get().secure())
        controller.pause().stop().destroy(); idle()
    }

    @Test fun onTheCarScreenItCountsAsShownWhileResumedOnly() {
        val controller = launch().setup(); idle()
        assertTrue(Screen.CAR in front.shown())
        assertNotEquals(CarAccess.PhoneDisplay, controller.get().vm.access.value)
        controller.pause(); idle()
        assertFalse(Screen.CAR in front.shown())
        controller.stop().destroy(); idle()
    }

    @Test fun onThePhonesOwnDisplayItShowsOnlyAMessageAndIsNeverCounted() {
        display = Display.DEFAULT_DISPLAY
        val controller = launch().setup(); idle()
        assertFalse("not the car in front", Screen.CAR in front.shown())
        assertEquals(CarAccess.PhoneDisplay, controller.get().vm.access.value)
        assertNull("no lists read", controller.get().vm.lists.value)
        controller.pause().resume(); idle()
        assertFalse(Screen.CAR in front.shown())
        controller.pause().stop().destroy(); idle()
    }

    @Test fun movedToThePhonesDisplayWhileShownItStopsCountingAndShowsTheMessage() {
        val controller = launch().setup(); idle()
        assertTrue(Screen.CAR in front.shown())
        display = Display.DEFAULT_DISPLAY // Android Auto went: the task moved to the phone, with no recreation (configChanges)
        controller.configurationChange(); idle()
        assertFalse(Screen.CAR in front.shown())
        assertEquals(CarAccess.PhoneDisplay, controller.get().vm.access.value)
        display = CAR_DISPLAY
        controller.configurationChange(); idle()
        assertTrue("back on a car display", Screen.CAR in front.shown())
        controller.pause().stop().destroy(); idle()
    }

    @Test fun theRecentAppsScreenKeepsNoPictureOfTheCarTask() {
        val controller = launch().create()
        assertEquals(listOf(false), recents)
        controller.start().resume().pause().stop().destroy(); idle()
    }

    private companion object {
        const val CAR_DISPLAY = 7
    }
}
