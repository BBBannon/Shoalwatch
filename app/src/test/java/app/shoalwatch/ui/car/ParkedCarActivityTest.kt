package app.shoalwatch.ui.car

import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.WindowManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Milestone 9: Android Auto blanks a `FLAG_SECURE` window on the car display, so the car activity's window is never secure: not
 * when created, not on the car screen, not after losing and
 * regaining it. Started with a hostile intent (data and extras), as any app on the phone could, it still starts and shows the car.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w800dp-h480dp-land")
class ParkedCarActivityTest {
    private fun ParkedCarActivity.secure() = window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE

    @Test fun theCarWindowIsNeverSecure() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("android-app://androidx.navigation/player/x")).putExtra("open_tab", "downloads")
        val controller = Robolectric.buildActivity(ParkedCarActivity::class.java, intent).create()
        assertEquals("created", 0, controller.get().secure())
        controller.start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("on the car screen", 0, controller.get().secure())
        controller.pause().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("after losing and regaining the screen", 0, controller.get().secure())
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
    }
}
