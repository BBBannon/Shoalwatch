package app.shoalwatch.ui.components

import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import java.util.WeakHashMap

/**
 * Keeps the window this is composed in out of autofill while it is in the composition (Tasks 5-6 review I1): the lock screen, the
 * "Forgot PIN" panel and every PIN field (so every PIN dialog's own window too). A password manager must not fill the locked
 * profile's server password for a child, nor offer to save a PIN. Counted per window like [SecureWindowFlag]: the window's root
 * gets `IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS` on every acquire, and its earlier value back only when the last holder leaves.
 */
@Composable
fun NoAutofill() {
    val root = LocalView.current.rootView
    DisposableEffect(root) {
        NoAutofillFlag.acquire(root)
        onDispose { NoAutofillFlag.release(root) }
    }
}

internal object NoAutofillFlag {
    private class Held(var count: Int, val before: Int)

    private val holders = WeakHashMap<View, Held>()

    fun acquire(root: View) {
        val held = holders[root]
        if (held == null) holders[root] = Held(1, root.importantForAutofill) else held.count++
        root.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
    }

    fun release(root: View) {
        val held = holders[root] ?: return
        if (--held.count <= 0) {
            holders.remove(root)
            root.importantForAutofill = held.before
        }
    }
}

/** Asks the keyboard not to learn or suggest from what is typed in [content]'s text fields (a PIN, the server password). */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NoKeyboardLearning(content: @Composable () -> Unit) {
    InterceptPlatformTextInput(
        interceptor = { request, next ->
            next.startInputMethod(
                PlatformTextInputMethodRequest { info ->
                    request.createInputConnection(info).also {
                        info.imeOptions = info.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                    }
                },
            )
        },
        content = content,
    )
}
