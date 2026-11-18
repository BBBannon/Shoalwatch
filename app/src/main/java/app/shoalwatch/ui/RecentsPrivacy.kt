package app.shoalwatch.ui

import android.app.Activity
import android.os.Build
import android.view.Window
import app.shoalwatch.ui.components.SecureWindowFlag
import java.util.WeakHashMap

/**
 * Hides the app's preview in the recent-apps screen while `LockGate.hideRecents` says so (Milestone 7).
 * API 33+: `Activity.setRecentsScreenshotEnabled(!hide)` (screenshots stay allowed). API 29–32: a `SecureWindowFlag` holder while
 * hidden, counted with every other holder, so releasing it never clears a flag an admin or sign-in screen still needs.
 */
internal object RecentsPrivacy {
    /** The API 33+ switch; a seam because Robolectric's shadow does not record it. */
    internal var recentsSwitch: (Activity, Boolean) -> Unit = { activity, enabled ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) activity.setRecentsScreenshotEnabled(enabled)
    }

    private val held = WeakHashMap<Window, Boolean>()

    fun apply(activity: Activity, hide: Boolean) = apply(activity, hide, Build.VERSION.SDK_INT)

    /** [sdk] is a parameter so the API 29–32 path is tested on the SDK the tests have. */
    internal fun apply(activity: Activity, hide: Boolean, sdk: Int) {
        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            recentsSwitch(activity, !hide)
            return
        }
        val window = activity.window ?: return
        val holding = held[window] == true
        if (hide && !holding) {
            SecureWindowFlag.acquire(window)
            held[window] = true
        } else if (!hide && holding) {
            held.remove(window)
            SecureWindowFlag.release(window)
        }
    }
}
