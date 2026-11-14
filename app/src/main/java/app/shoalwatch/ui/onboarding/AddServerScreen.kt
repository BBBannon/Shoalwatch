package app.shoalwatch.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.ui.components.AppScreen
import app.shoalwatch.ui.components.ErrorMessage
import app.shoalwatch.ui.components.PrimaryButton
import app.shoalwatch.ui.components.SecondaryButton
import app.shoalwatch.ui.components.ShoalMark

/** Everything the Add server screen can ask for, so the screen itself holds no logic. */
class AddServerActions(
    val onAddressChange: (String) -> Unit,
    val onContinue: () -> Unit,
    val onCancelCheck: () -> Unit,
    val onOfferHttp: () -> Unit,
    val onAllowHttp: () -> Unit,
    val onTrustCertificate: () -> Unit,
    val onChooseClientCertificate: () -> Unit,
    val onConfirmFound: () -> Unit,
    val onSecondAddressChange: (String) -> Unit,
    val onAddSecond: () -> Unit,
    val onSkipSecond: () -> Unit,
    val onBackToAddress: () -> Unit,
)

/**
 * The Add server flow, one full-screen step at a time. A warning step blocks everything until the user
 * chooses; steps cross-fade quickly, and each keeps the content it was showing while it fades out.
 *
 * @param onLeave back from the first step (null on the very first run, where there is nowhere to go back to).
 */
@Composable
fun AddServerScreen(state: AddServerUiState, actions: AddServerActions, onLeave: (() -> Unit)?) {
    BackHandler(enabled = state.check != Check.Idle || state.stage != AddServerStage.Address) {
        if (state.check != Check.Idle) actions.onCancelCheck() else actions.onBackToAddress()
    }
    AnimatedContent(
        targetState = state,
        contentKey = { gateKey(it.check, it.stage) },
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
        label = "add-server-step",
    ) { shown ->
        CheckGate(
            check = shown.check,
            role = shown.addressRole,
            onCancel = actions.onCancelCheck,
            onAllowHttp = actions.onAllowHttp,
            onTrustCertificate = actions.onTrustCertificate,
            onChooseClientCertificate = actions.onChooseClientCertificate,
        ) { checking, failed ->
            when (shown.stage) {
                AddServerStage.Address -> AddressStep(shown, checking, failed, actions, onLeave)
                AddServerStage.Found -> FoundStep(shown, actions)
                AddServerStage.SecondAddress -> SecondAddressStep(shown, checking, failed, actions)
            }
        }
    }
}

@Composable
private fun AddressStep(state: AddServerUiState, checking: Boolean, failed: Check.Failed?, actions: AddServerActions, onLeave: (() -> Unit)?) {
    val firstRun = onLeave == null
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    AppScreen(
        title = if (firstRun) "" else stringResource(R.string.add_title),
        onBack = onLeave,
        bottomBar = {
            PrimaryButton(
                stringResource(R.string.action_continue), actions.onContinue,
                enabled = state.address.isNotBlank(), loading = checking,
            )
            if (checking) SecondaryButton(stringResource(R.string.action_cancel), actions.onCancelCheck)
        },
    ) {
        if (firstRun) {
            Column(Modifier.fillMaxWidth().padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ShoalMark()
                Text(
                    stringResource(R.string.add_title_first),
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    stringResource(R.string.placeholder_tagline),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Text(stringResource(R.string.add_intro), style = MaterialTheme.typography.bodyLarge)
        AddressField(
            value = state.address,
            onChange = actions.onAddressChange,
            label = stringResource(R.string.add_address_label),
            isError = failed != null || state.error != null,
            enabled = !checking,
            onGo = actions.onContinue,
            modifier = Modifier.focusRequester(focus),
        )
        ErrorMessage(failed?.message ?: state.error)
        if (failed?.offerHttp == true) SecondaryButton(stringResource(R.string.add_try_http), actions.onOfferHttp)
    }
}

/** A URL field: no autocorrect or capitals, URI keyboard, "Go" submits, and an example always visible under it. */
@Composable
internal fun AddressField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    isError: Boolean,
    enabled: Boolean,
    onGo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label) },
        supportingText = { Text(stringResource(R.string.add_address_example)) },
        singleLine = true,
        isError = isError,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
    )
}
