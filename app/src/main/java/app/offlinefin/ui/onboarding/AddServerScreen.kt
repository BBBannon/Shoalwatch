package app.offlinefin.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.offlinefin.R
import app.offlinefin.ui.components.AppScreen
import app.offlinefin.ui.components.CertificateScreen
import app.offlinefin.ui.components.CheckingScreen
import app.offlinefin.ui.components.ClientCertificateScreen
import app.offlinefin.ui.components.ErrorMessage
import app.offlinefin.ui.components.HttpWarningScreen
import app.offlinefin.ui.components.PrimaryButton
import app.offlinefin.ui.components.SecondaryButton

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

private enum class Step { Address, Checking, Http, Certificate, ClientCertificate, Found, Second }

private fun stepOf(state: AddServerUiState): Step = when (state.check) {
    Check.Checking -> Step.Checking
    Check.NeedsHttpConsent -> Step.Http
    Check.LoadingCertificate, is Check.NeedsCertificateTrust -> Step.Certificate
    Check.NeedsClientCertificate -> Step.ClientCertificate
    else -> when (state.stage) {
        AddServerStage.Address -> Step.Address
        AddServerStage.Found -> Step.Found
        AddServerStage.SecondAddress -> Step.Second
    }
}

/**
 * The Add server flow, one full-screen step at a time. A warning step blocks everything until the user
 * chooses; the steps cross-fade quickly so nothing slides around.
 *
 * @param onLeave back from the first step (null on the very first run, where there is nowhere to go back to).
 */
@Composable
fun AddServerScreen(state: AddServerUiState, actions: AddServerActions, onLeave: (() -> Unit)?) {
    val step = stepOf(state)
    BackHandler(enabled = step != Step.Address) {
        when (step) {
            Step.Found, Step.Second -> actions.onBackToAddress()
            else -> actions.onCancelCheck()
        }
    }
    AnimatedContent(
        targetState = step,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
        label = "add-server-step",
    ) { target ->
        when (target) {
            Step.Address -> AddressStep(state, actions, onLeave)
            Step.Checking -> CheckingScreen(
                title = stringResource(R.string.add_title),
                detail = stringResource(R.string.add_checking),
                cancelLabel = stringResource(R.string.action_cancel),
                onCancel = actions.onCancelCheck,
            )
            Step.Http -> HttpWarningScreen(onCancel = actions.onCancelCheck, onUseAnyway = actions.onAllowHttp)
            Step.Certificate -> CertificateScreen(
                certificate = (state.check as? Check.NeedsCertificateTrust)?.certificate,
                loading = state.check == Check.LoadingCertificate,
                error = null,
                changed = false,
                onTrust = actions.onTrustCertificate,
                onCancel = actions.onCancelCheck,
            )
            Step.ClientCertificate -> ClientCertificateScreen(onChoose = actions.onChooseClientCertificate, onCancel = actions.onCancelCheck)
            Step.Found -> FoundStep(state, actions)
            Step.Second -> SecondAddressStep(state, actions)
        }
    }
}

@Composable
private fun AddressStep(state: AddServerUiState, actions: AddServerActions, onLeave: (() -> Unit)?) {
    val failed = state.check as? Check.Failed
    val firstRun = onLeave == null
    AppScreen(
        title = if (firstRun) "" else stringResource(R.string.add_title),
        onBack = onLeave,
        bottomBar = {
            PrimaryButton(stringResource(R.string.action_continue), actions.onContinue, enabled = state.address.isNotBlank())
        },
    ) {
        if (firstRun) {
            Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                    Box(Modifier.size(80.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(44.dp))
                    }
                }
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
            hint = stringResource(R.string.add_address_hint),
            isError = failed != null || state.error != null,
            onGo = actions.onContinue,
        )
        ErrorMessage(failed?.message ?: state.error)
        if (failed?.offerHttp == true) SecondaryButton(stringResource(R.string.add_try_http), actions.onOfferHttp)
    }
}

/** A URL field: no autocorrect or capitals, URI keyboard, "Go" submits. */
@Composable
internal fun AddressField(value: String, onChange: (String) -> Unit, label: String, hint: String, isError: Boolean, onGo: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        placeholder = { Text(hint) },
        singleLine = true,
        isError = isError,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { onGo() }),
    )
}
