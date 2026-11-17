package app.shoalwatch.ui.player

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Looper
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.R
import app.shoalwatch.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController

/**
 * Picture-in-picture (Milestone 7 Task 9): the window's params (aspect, one immutable explicit action, auto-enter while playing),
 * the run-time receiver, entering on the way out on Android 10-11, and the app lock's timer.
 */
@RunWith(RobolectricTestRunner::class)
class PictureInPictureTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun wish(w: Int = 1920, h: Int = 1080, playing: Boolean = true) = PipWish(w, h, playing, null)

    private var autoEnter = true
    private val gate = mutableListOf<String>()
    private val presses = mutableListOf<String>()

    @Before fun pipSupported() {
        shadowOf(context.packageManager).setSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
    }

    /** [sdkInt]: the Android version the host acts for (the suite runs one SDK; a second Robolectric SDK runs it out of memory). */
    private fun host(sdkInt: Int = Build.VERSION.SDK_INT): Pair<ActivityController<ComponentActivity>, ActivityPip> {
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        return controller to ActivityPip(controller.get(), { autoEnter }, { gate += "left" }, { gate += "returned" }, sdkInt)
    }

    @Test fun theAspectIsTheVideosClampedToWhatAndroidAllows() {
        assertEquals(Rational(16, 9), pipParams(context, wish()).aspectRatio)
        assertEquals(Rational(239, 100), pipParams(context, wish(4000, 1000)).aspectRatio)
        assertEquals(Rational(100, 239), pipParams(context, wish(1000, 4000)).aspectRatio)
        assertEquals("unknown size", Rational(16, 9), pipParams(context, wish(0, 0)).aspectRatio)
    }

    @Test fun oneActionAnImmutableBroadcastToThisPackageOnly() {
        val actions = pipParams(context, wish()).actions
        assertEquals(1, actions.size)
        val pending = actions.single().actionIntent
        assertTrue("immutable", pending.isImmutable)
        assertTrue("a broadcast", shadowOf(pending).isBroadcast)
        val sent = shadowOf(pending).savedIntent
        assertEquals("explicit: this package", context.packageName, sent.`package`)
        assertEquals(PipActionReceiver.ACTION, sent.action)
        assertEquals(context.getString(R.string.pip_pause), actions.single().title)
        assertEquals(context.getString(R.string.pip_play), pipParams(context, wish(playing = false)).actions.single().title)
    }

    @Test fun autoEnterOnlyWhilePlayingAndNoSeamlessResize() {
        assertTrue(pipParams(context, wish(playing = true)).isAutoEnterEnabled)
        assertFalse(pipParams(context, wish(playing = false)).isAutoEnterEnabled)
        assertFalse(pipParams(context, wish()).isSeamlessResizeEnabled)
    }

    @Test fun theReceiverAnswersOnlyItsOwnAction() {
        var n = 0
        val receiver = PipActionReceiver { n++ }
        receiver.onReceive(context, Intent("app.shoalwatch.action.SOMETHING_ELSE"))
        assertEquals(0, n)
        receiver.onReceive(context, Intent(PipActionReceiver.ACTION))
        assertEquals(1, n)
    }

    @Test fun theReceiverIsRegisteredOnlyWhileAWishExists() {
        val (controller, pip) = host()
        try {
            pip.wish(wish()) { presses += "playPause" }
            context.sendBroadcast(Intent(PipActionReceiver.ACTION).setPackage(context.packageName))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(listOf("playPause"), presses)
            pip.wish(null, null)
            context.sendBroadcast(Intent(PipActionReceiver.ACTION).setPackage(context.packageName))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("unregistered with the wish", listOf("playPause"), presses)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun onAndroid11LeavingWhilePlayingEntersPip() {
        val (controller, pip) = host(sdkInt = 30)
        try {
            pip.wish(wish(playing = true)) {}
            controller.userLeaving()
            assertTrue(controller.get().isInPictureInPictureMode)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun onAndroid11LeavingPausedOrWithTheSettingOffOrNoWishDoesNot() {
        for (case in listOf("paused", "setting off", "no wish")) {
            autoEnter = case != "setting off"
            val (controller, pip) = host(sdkInt = 30)
            try {
                if (case != "no wish") pip.wish(wish(playing = case != "paused")) {}
                controller.userLeaving()
                assertFalse(case, controller.get().isInPictureInPictureMode)
            } finally { controller.pause().stop().destroy() }
        }
    }

    @Test fun theButtonEntersWheneverAWishExistsEvenWithTheSettingOff() {
        autoEnter = false
        val (controller, pip) = host(sdkInt = 30)
        try {
            assertFalse("no wish, no window", pip.enterNow())
            pip.wish(wish(playing = false)) {}
            assertTrue(pip.enterNow())
            assertTrue(controller.get().isInPictureInPictureMode)
        } finally { controller.pause().stop().destroy() }
    }

    /**
     * `MainActivity` is the host: it forwards to [ActivityPip]. Built but not created, so no screen, graph start-up or database
     * is involved (a full launch on another SDK would load Robolectric's SQLite into that SDK's sandbox for the rest of the run).
     * On Android 12+ leaving does not enter by hand: the system's auto-enter does it during the gesture.
     */
    @Test fun theMainActivityIsTheHost() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        val host = activity as PipHost
        assertFalse("no wish, no window", host.enterNow())
        host.wish(wish(playing = true)) {}
        controller.userLeaving()
        assertFalse("Android 12+: no entry from onUserLeaveHint", activity.isInPictureInPictureMode)
        assertTrue(host.enterNow())
        assertTrue(activity.isInPictureInPictureMode)
        activity.onPictureInPictureModeChanged(true, Configuration())
        assertTrue(host.inPip.value)
        host.wish(null, null)
    }

    @Test fun enteringCountsAsLeavingAndExpandingAsReturning() {
        val (controller, pip) = host()
        try {
            controller.get().onPictureInPictureModeChanged(true, Configuration())
            assertTrue(pip.inPip.value)
            assertEquals(listOf("left"), gate)
            controller.get().onPictureInPictureModeChanged(false, Configuration())
            assertFalse(pip.inPip.value)
            assertEquals(listOf("left", "returned"), gate)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun closingTheWindowIsNotReturning() {
        val (controller, pip) = host()
        try {
            controller.get().onPictureInPictureModeChanged(true, Configuration())
            controller.pause().stop() // the window closed: the activity stops before Android says PiP ended
            controller.get().onPictureInPictureModeChanged(false, Configuration())
            assertFalse(pip.inPip.value)
            assertEquals(listOf("left"), gate)
        } finally { controller.destroy() }
    }
}
