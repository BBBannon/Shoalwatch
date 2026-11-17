package app.shoalwatch.ui.lock

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.foundation.text.input.then
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import app.shoalwatch.security.PinRules

/** Digits 0-9 only: anything else typed or pasted is refused whole. */
private val DigitsOnly = InputTransformation {
    if (!asCharSequence().all { it in '0'..'9' }) revertAllChanges()
}

/**
 * The one PIN field (Milestone 7): a number pad, always hidden (never even the last digit), no autocorrect, at most
 * [PinRules.MAX] digits. Like the sign-in password, accessibility sees only bullets of the same length on the node flagged as a
 * password, never the digits. The PIN lives only in [state].
 */
@Composable
fun PinField(state: TextFieldState, label: String, onDone: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedSecureTextField(
        state = state,
        modifier = modifier.fillMaxWidth().semantics {
            password()
            editableText = AnnotatedString("•".repeat(state.text.length))
        },
        label = { Text(label) },
        enabled = enabled,
        inputTransformation = InputTransformation.maxLength(PinRules.MAX).then(DigitsOnly),
        textObfuscationMode = TextObfuscationMode.Hidden,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        onKeyboardAction = KeyboardActionHandler { onDone() },
    )
}
