package app.shoalwatch.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.KeyboardActionHandler
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.editableText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.servers.AuthRepository
import app.shoalwatch.ui.components.ErrorMessage
import app.shoalwatch.ui.components.Loading
import app.shoalwatch.ui.components.RevealToggle
import app.shoalwatch.ui.theme.CodeStyles

private val TotalSeconds = (AuthRepository.TIMEOUT_MS / 1_000).toInt()

@Composable
internal fun QuickConnectPanel(ui: QuickConnectUi) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (ui) {
            QuickConnectUi.Starting -> Loading(stringResource(R.string.qc_starting))
            QuickConnectUi.Authenticating -> Loading(stringResource(R.string.qc_authenticating))
            is QuickConnectUi.Code -> CodeCard(ui)
            QuickConnectUi.TimedOut -> ErrorMessage(stringResource(R.string.qc_timed_out))
            is QuickConnectUi.Failed -> ErrorMessage(ui.message)
        }
    }
}

/** The Quick Connect code, large and grouped, with what to do with it and a quiet countdown. */
@Composable
private fun CodeCard(ui: QuickConnectUi.Code) {
    val spoken = stringResource(R.string.qc_code_cd, ui.code.toList().joinToString(" "))
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(
                ui.code.chunked(3).joinToString(" "),
                style = CodeStyles.Code,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { contentDescription = spoken },
            )
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.qc_step_1, R.string.qc_step_2, R.string.qc_step_3).forEachIndexed { index, step ->
                    Text("${index + 1}. " + stringResource(step), style = MaterialTheme.typography.bodyLarge)
                }
            }
            LinearProgressIndicator(progress = { ui.secondsLeft / TotalSeconds.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text(
                stringResource(R.string.qc_expires, stringResource(R.string.duration_minutes_seconds, ui.secondsLeft / 60, ui.secondsLeft % 60)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Username and password. Both carry autofill hints so a password manager can fill them; the keyboard is told
 * not to learn or correct the password. The password lives only in [password], never in the state.
 */
@Composable
internal fun PasswordPanel(state: SignInUiState, password: TextFieldState, actions: SignInActions) {
    var revealed by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = state.username,
            onValueChange = actions.onUsernameChange,
            modifier = Modifier.fillMaxWidth().semantics { contentType = ContentType.Username },
            label = { Text(stringResource(R.string.username_label)) },
            singleLine = true,
            enabled = !state.submitting,
            isError = state.error != null,
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next,
            ),
        )
        OutlinedSecureTextField(
            state = password,
            modifier = Modifier.fillMaxWidth().semantics {
                contentType = ContentType.Password
                // The field exposes its raw text to accessibility services as EditableText even though it is drawn
                // masked; this outer modifier replaces it with bullets of the same length (the Password flag stays).
                editableText = AnnotatedString("•".repeat(password.text.length))
            },
            label = { Text(stringResource(R.string.password_label)) },
            enabled = !state.submitting,
            isError = state.error != null,
            textObfuscationMode = if (revealed) TextObfuscationMode.Visible else TextObfuscationMode.RevealLastTyped,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Go),
            onKeyboardAction = KeyboardActionHandler { actions.onSignIn() },
            trailingIcon = {
                RevealToggle(revealed, { revealed = !revealed }, stringResource(if (revealed) R.string.password_hide_cd else R.string.password_show_cd))
            },
        )
        ErrorMessage(state.error)
    }
}
