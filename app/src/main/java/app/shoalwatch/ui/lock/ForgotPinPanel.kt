package app.shoalwatch.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.core.AppError
import app.shoalwatch.ui.components.ErrorMessage
import app.shoalwatch.ui.components.NoAutofill
import app.shoalwatch.ui.components.NoKeyboardLearning
import app.shoalwatch.ui.components.PrimaryButton
import app.shoalwatch.ui.components.SecureWindow

/**
 * "Forgot PIN" inside the lock screen: the locked profile's server password, which removes the PIN. The window is
 * `FLAG_SECURE` while this shows ([SecureWindow]); the password field is masked to accessibility like the sign-in one. No Quick
 * Connect here (see the plan's Decisions). The window is kept out of autofill and the keyboard asked not to learn the password
 * ([NoAutofill], review I1): the password must be typed, not filled by a password manager.
 */
@Composable
internal fun ForgotPinPanel(state: LockUiState, password: TextFieldState, onSubmit: () -> Unit, onCancel: () -> Unit) {
    SecureWindow()
    NoAutofill()
    val server = state.lockedServerName.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.lock_forgot_body, server), style = MaterialTheme.typography.bodyLarge)
        NoKeyboardLearning {
            OutlinedSecureTextField(
                state = password,
                modifier = Modifier.fillMaxWidth().semantics {
                    password()
                    editableText = AnnotatedString("•".repeat(password.text.length))
                },
                label = { Text(stringResource(R.string.password_label)) },
                enabled = !state.busy,
                textObfuscationMode = TextObfuscationMode.RevealLastTyped,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Go),
                onKeyboardAction = KeyboardActionHandler { onSubmit() },
            )
        }
        ErrorMessage(forgotErrorText(state, server))
        PrimaryButton(stringResource(R.string.lock_forgot_submit), onSubmit, loading = state.busy)
        TextButton(onClick = onCancel, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text(stringResource(R.string.action_cancel))
        }
    }
}

@Composable
private fun forgotErrorText(state: LockUiState, server: String): String? = when {
    state.forgotOtherAccount -> stringResource(R.string.lock_forgot_other_account)
    state.forgotError == AppError.Kind.OFFLINE -> stringResource(R.string.lock_forgot_offline, server)
    state.forgotError != null -> AppError.of(state.forgotError).userMessage
    else -> null
}
