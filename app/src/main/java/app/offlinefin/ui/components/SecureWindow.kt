package app.offlinefin.ui.components

import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import app.offlinefin.ui.findActivity

/**
 * Marks the window `FLAG_SECURE` while this is in the composition, so passwords, header values and
 * fingerprints stay out of screenshots and the recent-apps preview. Counted, so two overlapping screens
 * during a transition cannot clear each other's flag.
 */
@Composable
fun SecureWindow() {
    val window = LocalContext.current.findActivity()?.window
    DisposableEffect(window) {
        if (window != null) SecureWindowFlag.acquire(window)
        onDispose { if (window != null) SecureWindowFlag.release(window) }
    }
}

private object SecureWindowFlag {
    private var holders = 0

    fun acquire(window: Window) {
        if (holders++ == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    fun release(window: Window) {
        if (--holders <= 0) {
            holders = 0
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
