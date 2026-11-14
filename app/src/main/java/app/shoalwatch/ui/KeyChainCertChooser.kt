package app.shoalwatch.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.security.KeyChain
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** The Activity behind this context, or null. */
fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * The system certificate picker. It needs an Activity, so it is made here, in the screen, and handed to a
 * ViewModel for a single call.
 */
@Composable
fun rememberClientCertChooser(): ClientCertChooser {
    val activity = LocalContext.current.findActivity()
    return remember(activity) {
        ClientCertChooser {
            if (activity == null) {
                null
            } else {
                suspendCancellableCoroutine { continuation ->
                    KeyChain.choosePrivateKeyAlias(
                        activity,
                        { alias -> if (continuation.isActive) continuation.resume(alias) },
                        null, null, null, -1, null,
                    )
                }
            }
        }
    }
}
