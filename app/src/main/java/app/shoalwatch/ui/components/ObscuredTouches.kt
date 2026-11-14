package app.shoalwatch.ui.components

import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import java.util.WeakHashMap

/**
 * Tapjacking defence: while this is in the composition, the window hosting it ignores touches that arrive while
 * another app's window covers it (Android flags them `FLAG_WINDOW_IS_OBSCURED`; with `filterTouchesWhenObscured`
 * set on the window's root view they are dropped before Compose sees them). Android 12+ already blocks most
 * untrusted overlays; Android 10 and 11 (minSdk 29) do not.
 *
 * Used by the two choices that weaken security ("Use anyway", "Trust this certificate", via [HttpWarningScreen]
 * and [CertificateScreen]) and inside every [SecureDialog] window ([ConfirmDialog] and the header editor), since a
 * dialog is a window of its own. Counted per root view, like [SecureWindow]: set on every acquire, cleared only
 * when the last holder leaves.
 */
@Composable
fun FilterObscuredTouches() {
    val root = LocalView.current.rootView
    DisposableEffect(root) {
        ObscuredTouchFilter.acquire(root)
        onDispose { ObscuredTouchFilter.release(root) }
    }
}

internal object ObscuredTouchFilter {
    private val holders = WeakHashMap<View, Int>()

    fun acquire(view: View) {
        holders[view] = (holders[view] ?: 0) + 1
        view.filterTouchesWhenObscured = true
    }

    fun release(view: View) {
        val remaining = (holders[view] ?: 0) - 1
        if (remaining <= 0) {
            holders.remove(view)
            view.filterTouchesWhenObscured = false
        } else {
            holders[view] = remaining
        }
    }
}
