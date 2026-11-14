package app.offlinefin.ui.components

import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import app.offlinefin.ui.findActivity
import java.util.WeakHashMap

/**
 * Marks the window `FLAG_SECURE` while this is in the composition, so passwords, header values and
 * fingerprints stay out of screenshots and the recent-apps preview. Dialogs are separate windows and are
 * secured through `DialogProperties(securePolicy = SecureOn)` instead.
 *
 * Fail-safe: counted per window, the flag is added on every acquire (so it can never be missing while a
 * holder exists) and cleared only when the last holder of that window releases.
 */
@Composable
fun SecureWindow() {
    val window = LocalContext.current.findActivity()?.window
    DisposableEffect(window) {
        if (window != null) SecureWindowFlag.acquire(window)
        onDispose { if (window != null) SecureWindowFlag.release(window) }
    }
}

internal object SecureWindowFlag {
    private val holders = WeakHashMap<Window, Int>()

    fun acquire(window: Window) {
        holders[window] = (holders[window] ?: 0) + 1
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(window: Window) {
        val remaining = (holders[window] ?: 0) - 1
        if (remaining <= 0) {
            holders.remove(window)
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            holders[window] = remaining
        }
    }
}
