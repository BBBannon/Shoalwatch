package app.shoalwatch.ui.admin

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.ui.components.FilterObscuredTouches
import app.shoalwatch.ui.components.ObscuredTouchHint
import app.shoalwatch.ui.components.SecureDialog
import app.shoalwatch.ui.components.LocalCovered
import app.shoalwatch.ui.components.NoKeyboardLearning

// Admin dialogs are windows of their own: each sets the secure flag (SecureDialog) and refuses touches from behind another
// app's window (FilterObscuredTouches). Cancel is always the dismiss button; the confirm button is in the error colour.

/** "Stop kid's playback?" — [title] names the target; [body] is one paragraph per entry. */
@Composable
fun AdminConfirmDialog(title: String, body: List<String>, confirmLabel: String, enabled: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AdminDialog(title, confirmLabel, enabled, onConfirm, onDismiss) {
        body.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

/** Restart and shut down: the confirm button works only once [word] has been typed (trimmed, any case, spaces collapsed). */
@Composable
fun TypedConfirmDialog(
    title: String,
    body: String,
    word: String,
    typed: TextFieldState,
    confirmLabel: String,
    enabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val matches = typedMatches(typed.text, word)
    AdminDialog(title, confirmLabel, enabled && matches, onConfirm, onDismiss) {
        Text(body, style = MaterialTheme.typography.bodyMedium)
        NoKeyboardLearning {
            OutlinedTextField(
                state = typed,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.admin_type_to_confirm, word)) },
                supportingText = if (typed.text.isNotEmpty() && !matches) ({ Text(stringResource(R.string.admin_type_exactly)) }) else null,
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
            )
        }
    }
}

/** A message for someone's screen: at most 200 characters, with a counter; Send works only for text with something in it. */
@Composable
fun MessageDialog(title: String, body: String, text: TextFieldState, enabled: Boolean, onSend: () -> Unit, onDismiss: () -> Unit) {
    AdminDialog(title, stringResource(R.string.admin_message_send), enabled && text.text.isNotBlank(), onSend, onDismiss) {
        Text(body, style = MaterialTheme.typography.bodyMedium)
        NoKeyboardLearning {
            OutlinedTextField(
                state = text,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.admin_message_label)) },
                inputTransformation = InputTransformation.maxLength(MESSAGE_MAX),
                supportingText = { Text(stringResource(R.string.admin_message_counter, text.text.length.toString())) },
                lineLimits = TextFieldLineLimits.MultiLine(minHeightInLines = 2, maxHeightInLines = 5),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, keyboardType = KeyboardType.Text),
            )
        }
    }
}

/** A confirmation with its own controls (Milestone 10: the refresh choices, "Replace all pictures"); the same secure window. */
@Composable
fun AdminFormDialog(title: String, confirmLabel: String, enabled: Boolean, onConfirm: () -> Unit, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    AdminDialog(title, confirmLabel, enabled, onConfirm, onDismiss, content)
}

@Composable
private fun AdminDialog(
    title: String,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    if (LocalCovered.current) return // under the lock cover (review M1)
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = SecureDialog,
        title = { Text(title, Modifier.semantics { heading() }) },
        text = {
            val obscured = FilterObscuredTouches()
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ObscuredTouchHint(obscured)
                content()
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = confirmEnabled,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(confirmLabel) }
        },
    )
}

private const val MESSAGE_MAX = 200
