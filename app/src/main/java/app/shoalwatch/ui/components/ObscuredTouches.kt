package app.shoalwatch.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.MotionEvent
import android.view.View
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogWindowProvider
import app.shoalwatch.R
import java.util.WeakHashMap

/**
 * Tapjacking defence: while this is in the composition, the window hosting it ignores touches that arrive while
 * another app's window covers it, and says so. Two layers on that window:
 * - the window's callback drops any touch Android flags `FLAG_WINDOW_IS_OBSCURED` or `FLAG_WINDOW_IS_PARTIALLY_OBSCURED`
 *   before Compose sees it, and raises the hint this function returns (shown with [ObscuredTouchHint]), so the user
 *   is not left with a button that silently does nothing; a gesture already begun is cancelled, never left half-pressed;
 * - `filterTouchesWhenObscured` on the root view stays on as the backstop (the system drops what the callback missed).
 * Android 12+ already blocks most untrusted overlays; Android 10 and 11 (minSdk 29) do not.
 *
 * Used by the two choices that weaken security ("Use anyway", "Trust this certificate", via [HttpWarningScreen]
 * and [CertificateScreen]) and inside every [SecureDialog] window ([ConfirmDialog] and the header editor), since a
 * dialog is a window of its own. Counted per root view, like [SecureWindow]: installed on every acquire, removed
 * only when the last holder leaves.
 *
 * @return true while the last touch was dropped because another app was drawn over this window.
 */
@Composable
fun FilterObscuredTouches(): Boolean {
    val view = LocalView.current
    val root = view.rootView
    val window = (view.parent as? DialogWindowProvider)?.window ?: view.context.findActivity()?.window
    val guard = remember(root, window) { ObscuredTouchFilter.guardFor(root) }
    DisposableEffect(root, window) {
        ObscuredTouchFilter.acquire(root, window)
        onDispose { ObscuredTouchFilter.release(root) }
    }
    return guard.hint.value
}

/** The one-line explanation shown on a protected screen after a touch was dropped; nothing otherwise. */
@Composable
fun ObscuredTouchHint(show: Boolean) {
    if (show) ErrorMessage(stringResource(R.string.obscured_hint))
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal class ObscuredGuard {
    val hint: MutableState<Boolean> = mutableStateOf(false)
    var holders = 0
    var window: Window? = null
    var original: Window.Callback? = null
}

internal object ObscuredTouchFilter {
    private val guards = WeakHashMap<View, ObscuredGuard>()
    private const val OBSCURED = MotionEvent.FLAG_WINDOW_IS_OBSCURED or MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED

    fun guardFor(view: View): ObscuredGuard = synchronized(guards) { guards.getOrPut(view) { ObscuredGuard() } }

    fun acquire(view: View, window: Window?) {
        val guard = guardFor(view)
        guard.holders++
        view.filterTouchesWhenObscured = true
        if (window != null && guard.window == null) {
            val original = window.callback ?: return
            guard.window = window
            guard.original = original
            window.callback = Filtering(original, guard)
        }
    }

    fun release(view: View) {
        val guard = guardFor(view)
        guard.holders--
        if (guard.holders <= 0) {
            // The guard stays in the map (weakly, by view) so a composable holding it never holds a stale copy; it is reset here.
            view.filterTouchesWhenObscured = false
            guard.hint.value = false
            val window = guard.window
            // Only put the original back if nobody wrapped ours in the meantime.
            if (window != null && window.callback is Filtering) window.callback = guard.original
            guard.window = null
        }
    }

    /** Drops obscured touches (and the rest of a gesture whose start was refused); everything else goes to [original]. */
    private class Filtering(private val original: Window.Callback, private val guard: ObscuredGuard) : Window.Callback by original {
        private var forwarding = false

        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.flags and OBSCURED != 0) {
                guard.hint.value = true
                if (forwarding) {
                    // A gesture already reached the screen: end it cleanly rather than leave a button pressed.
                    forwarding = false
                    val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                    original.dispatchTouchEvent(cancel)
                    cancel.recycle()
                }
                return true
            }
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                forwarding = true
                guard.hint.value = false
            } else if (!forwarding) {
                return true // the rest of a gesture whose start was dropped
            }
            val handled = original.dispatchTouchEvent(event)
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) forwarding = false
            return handled
        }
    }
}
