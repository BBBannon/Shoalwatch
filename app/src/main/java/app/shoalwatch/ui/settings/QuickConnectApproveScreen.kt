package app.shoalwatch.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.maxLength
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import app.shoalwatch.R
import app.shoalwatch.data.servers.ApproveOutcome
import app.shoalwatch.data.servers.QuickConnectApprover
import app.shoalwatch.ui.components.AppScreen
import app.shoalwatch.ui.components.ErrorMessage
import app.shoalwatch.ui.components.NoKeyboardLearning
import app.shoalwatch.ui.components.PrimaryButton
import app.shoalwatch.ui.components.SignedOutNotice
import app.shoalwatch.ui.lock.PinDialog

class QuickConnectActions(
    val onBack: () -> Unit,
    val onApprove: () -> Unit,
    val onOpenServers: () -> Unit = {},
    val onSubmitPin: () -> Unit = {},
    val onCancelPin: () -> Unit = {},
)

/**
 * Approve a Quick Connect code shown on a TV or laptop. Says first whose sign-in the other device will get and warns to approve only
 * a code on one's own device, then the code field (up to 9 characters typed, so "123 456" fits; letters and digits), Approve, and
 * the answer as a polite live region. A PIN-protected profile is asked for its PIN (the secure PIN dialog) before anything is sent.
 */
@Composable
fun QuickConnectApproveScreen(state: QuickConnectUiState, code: TextFieldState, actions: QuickConnectActions, pin: TextFieldState = TextFieldState()) {
    AppScreen(title = stringResource(R.string.qc_title), onBack = actions.onBack) {
        if (state.session == null) {
            SignedOutNotice(actions.onOpenServers)
            return@AppScreen
        }
        Text(stringResource(R.string.qc_body, state.userName), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.qc_how), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.qc_warning), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        NoKeyboardLearning {
            OutlinedTextField(
                state = code,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.qc_code_label)) },
                inputTransformation = InputTransformation.maxLength(MAX_TYPED),
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Characters, autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done,
                ),
            )
        }
        val ready = QuickConnectApprover.normalise(code.text.toString()) != null
        PrimaryButton(stringResource(R.string.qc_approve), actions.onApprove, Modifier.fillMaxWidth(), enabled = ready && !state.running, loading = state.running)
        state.outcome?.let { outcome ->
            val text = stringResource(outcomeText(outcome))
            if (outcome == ApproveOutcome.APPROVED) {
                Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            } else {
                ErrorMessage(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
    }
    if (state.pinAsk && state.session != null) {
        PinDialog(
            title = stringResource(R.string.qc_pin_title),
            body = stringResource(R.string.qc_pin_body, state.userName),
            pin = pin,
            answer = state.pinAnswer,
            confirmLabel = stringResource(R.string.qc_pin_confirm),
            onConfirm = actions.onSubmitPin,
            onDismiss = actions.onCancelPin,
            failed = state.pinFailed,
            busy = state.running,
        )
    }
}

internal fun outcomeText(o: ApproveOutcome): Int = when (o) {
    ApproveOutcome.APPROVED -> R.string.qc_approved
    ApproveOutcome.BAD_CODE -> R.string.qc_bad_code
    ApproveOutcome.NOT_FOUND -> R.string.qc_not_found
    ApproveOutcome.SWITCHED_OFF -> R.string.qc_switched_off
    ApproveOutcome.REFUSED -> R.string.qc_refused
    ApproveOutcome.OFFLINE -> R.string.qc_offline
    ApproveOutcome.SIGNED_OUT -> R.string.qc_signed_out
    ApproveOutcome.FAILED -> R.string.qc_failed
}

private const val MAX_TYPED = 9
