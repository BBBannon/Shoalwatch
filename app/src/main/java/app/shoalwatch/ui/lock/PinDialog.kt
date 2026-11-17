package app.shoalwatch.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.security.PinCheck
import app.shoalwatch.ui.components.ErrorMessage
import app.shoalwatch.ui.components.FilterObscuredTouches
import app.shoalwatch.ui.components.ObscuredTouchHint
import app.shoalwatch.ui.components.SecureDialog

/**
 * A PIN asked for in a dialog (switching into a protected profile, signing one out, "Use another profile" on the lock screen). A
 * [SecureDialog] window (kept out of screenshots and recents) behind the obscured-touch filter; the field is focused first.
 * [failed]: the try could not be saved (see [pinAnswerText]).
 */
@Composable
internal fun PinDialog(
    title: String,
    body: String,
    pin: TextFieldState,
    answer: PinCheck?,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    failed: Boolean = false,
    busy: Boolean = false,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = SecureDialog,
        title = { Text(title, Modifier.semantics { heading() }) },
        text = {
            val obscured = FilterObscuredTouches()
            val focus = remember { FocusRequester() }
            LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ObscuredTouchHint(obscured)
                Text(body)
                PinField(pin, stringResource(R.string.pin_label), onConfirm, Modifier.focusRequester(focus), enabled = !busy)
                ErrorMessage(pinAnswerText(answer, failed))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text(confirmLabel) }
        },
    )
}
