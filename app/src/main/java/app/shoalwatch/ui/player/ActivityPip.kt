package app.shoalwatch.ui.player

import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import app.shoalwatch.core.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The activity's [PipHost] (`MainActivity` hands its three members here). Android 12+ enters by itself during the swipe home
 * (`setAutoEnterEnabled` while playing and [autoEnter]): that is the fix for gesture navigation, where trying in
 * `onUserLeaveHint` after the gesture fails (the official app's Android 14+ bug). Android 10–11 enters from `onUserLeaveHint`.
 *
 * Entering counts as leaving for the app lock ([onLeft]); back to the full screen counts as returning ([onReturned]). Closing
 * the window stops the activity first, so it is not a return: the activity's own stop and start ([onActivityStopped],
 * [onActivityStarted]) count that trip, and the player pauses on the stop as it always has. A start while still in the small
 * window (the screen off and on, a recreation there) is not a return either: only the expand is (review C1 of Tasks 9-10).
 */
internal class ActivityPip(
    private val activity: ComponentActivity,
    private val autoEnter: () -> Boolean,
    private val onLeft: () -> Unit,
    private val onReturned: () -> Unit,
    /** The Android version (a parameter so the 10–11 path is tested on the one SDK the suite runs, as `SettingsScreen` does). */
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : PipHost {
    private val mutableInPip = MutableStateFlow(false)
    override val inPip: StateFlow<Boolean> = mutableInPip.asStateFlow()

    private var current: PipWish? = null
    private var onPlayPause: (() -> Unit)? = null
    private var receiver: PipActionReceiver? = null

    init {
        activity.addOnUserLeaveHintListener { onUserLeaveHint() }
        activity.addOnPictureInPictureModeChangedListener { onModeChanged(it.isInPictureInPictureMode) }
    }

    private val supported: Boolean
        get() = activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)

    override fun wish(w: PipWish?, onPlayPause: (() -> Unit)?) {
        current = w
        this.onPlayPause = onPlayPause
        if (w != null) register() else unregister()
        // The player went while in the small window (the last episode ended, playback failed, the lock cover): close the window
        // rather than show the page beneath in it (review M1 of Tasks 9-10). That stops the activity: leaving, as any close is.
        if (w == null && mutableInPip.value && !activity.isChangingConfigurations) activity.moveTaskToBack(false)
        if (!supported) return
        // Android 10–11 has no auto-enter, so nothing needs withdrawing there; the params are still kept current for the button.
        if (w == null && sdkInt < Build.VERSION_CODES.S) return
        try {
            activity.setPictureInPictureParams(if (w != null) pipParams(activity, w, w.playing && autoEnter()) else noPipParams())
        } catch (e: IllegalStateException) {
            AppLog.w(TAG, "picture-in-picture params refused", e)
        } catch (e: IllegalArgumentException) { // an aspect outside this device's range (review M4 of Tasks 9-10)
            AppLog.w(TAG, "picture-in-picture params refused", e)
        }
    }

    override fun enterNow(): Boolean {
        val w = current ?: return false
        if (!supported) return false
        return try {
            activity.enterPictureInPictureMode(pipParams(activity, w, w.playing && autoEnter()))
        } catch (e: IllegalStateException) {
            AppLog.w(TAG, "picture-in-picture refused", e)
            false
        } catch (e: IllegalArgumentException) {
            AppLog.w(TAG, "picture-in-picture refused", e)
            false
        }
    }

    /**
     * The activity started. In the small window this is not a return (review C1 of Tasks 9-10): the timer from entering stays,
     * so expanding after "Lock after" still locks. The window state is read here too, as an activity recreated in the small
     * window never hears the mode change (review M3).
     */
    fun onActivityStarted() {
        val inPip = activity.isInPictureInPictureMode
        mutableInPip.value = inPip
        if (!inPip) onReturned()
    }

    /** The activity stopped: leaving, unless it is only a configuration change (turning the phone, resizing the window). */
    fun onActivityStopped() {
        if (!activity.isChangingConfigurations) onLeft()
    }

    private fun onUserLeaveHint() {
        if (sdkInt >= Build.VERSION_CODES.S) return // auto-enter does it, during the gesture
        if (current?.playing == true && autoEnter()) enterNow()
    }

    private fun onModeChanged(inPip: Boolean) {
        mutableInPip.value = inPip
        when {
            inPip -> onLeft()
            // Back to the full screen. Closing the window stops the activity before this is said: not a return.
            activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) -> onReturned()
        }
    }

    private fun register() {
        if (receiver != null) return
        val r = PipActionReceiver { onPlayPause?.invoke() }
        ContextCompat.registerReceiver(activity, r, IntentFilter(PipActionReceiver.ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = r
    }

    private fun unregister() {
        val r = receiver ?: return
        receiver = null
        activity.unregisterReceiver(r)
    }
}

private const val TAG = "Pip"
