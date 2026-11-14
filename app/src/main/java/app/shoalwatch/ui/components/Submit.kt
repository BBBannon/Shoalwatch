package app.shoalwatch.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * Wraps a form's submit so the software keyboard is hidden first: the result, the error and the next actions
 * are then visible, not underneath the keyboard. Use the same wrapped action for the button and the keyboard's
 * Go/Done key.
 */
@Composable
fun rememberSubmit(action: () -> Unit): () -> Unit {
    val keyboard = LocalSoftwareKeyboardController.current
    val latest = rememberUpdatedState(action)
    return remember(keyboard) {
        {
            keyboard?.hide()
            latest.value()
        }
    }
}
