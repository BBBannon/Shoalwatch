package app.offlinefin.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.offlinefin.R
import app.offlinefin.ui.components.AppScreen
import app.offlinefin.ui.components.ErrorMessage
import app.offlinefin.ui.components.PrimaryButton
import app.offlinefin.ui.components.SecondaryButton
import app.offlinefin.ui.components.SecureWindow

class SignInActions(
    val onUsernameChange: (String) -> Unit,
    val onSignIn: () -> Unit,
    val onUsePassword: () -> Unit,
    val onUseQuickConnect: () -> Unit,
    val onRetryQuickConnect: () -> Unit,
    val onBack: () -> Unit,
)

/**
 * Sign in: Quick Connect first, password as the alternative. The window is marked secure while this is on
 * screen, so the password and Quick Connect code stay out of screenshots and the recent-apps preview.
 */
@Composable
fun SignInScreen(state: SignInUiState, password: TextFieldState, actions: SignInActions) {
    SecureWindow()
    val quick = state.mode == SignInMode.QuickConnect
    AppScreen(
        title = stringResource(R.string.signin_title),
        onBack = actions.onBack,
        bottomBar = {
            if (quick) {
                when (state.quickConnect) {
                    QuickConnectUi.TimedOut -> PrimaryButton(stringResource(R.string.qc_new_code), actions.onRetryQuickConnect)
                    is QuickConnectUi.Failed -> PrimaryButton(stringResource(R.string.action_retry), actions.onRetryQuickConnect)
                    else -> Unit
                }
                SecondaryButton(stringResource(R.string.qc_use_password), actions.onUsePassword)
            } else {
                PasswordActions(state, password, actions)
            }
        },
    ) {
        state.serverName?.let {
            Text(
                stringResource(R.string.signin_to, it),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.serverMissing) ErrorMessage(stringResource(R.string.server_gone))
        AnimatedContent(
            targetState = state.mode,
            transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
            label = "sign-in-mode",
        ) { mode ->
            Column {
                when (mode) {
                    SignInMode.QuickConnect -> QuickConnectPanel(state.quickConnect)
                    SignInMode.Password -> PasswordPanel(state, password, actions)
                }
            }
        }
    }
}

@Composable
private fun PasswordActions(state: SignInUiState, password: TextFieldState, actions: SignInActions) {
    PrimaryButton(
        text = stringResource(if (state.submitting) R.string.signin_signing else R.string.signin_action),
        onClick = actions.onSignIn,
        enabled = state.username.isNotBlank() && password.text.isNotEmpty(),
        loading = state.submitting,
    )
    if (state.quickConnectUnavailable) {
        Text(
            stringResource(R.string.qc_unavailable),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        SecondaryButton(stringResource(R.string.qc_use_qc), actions.onUseQuickConnect, enabled = !state.submitting)
    }
}
