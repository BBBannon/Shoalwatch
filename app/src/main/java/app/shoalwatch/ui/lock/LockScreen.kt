package app.shoalwatch.ui.lock

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.security.LockState
import app.shoalwatch.ui.components.ErrorMessage
import app.shoalwatch.ui.components.NoAutofill
import app.shoalwatch.ui.components.PrimaryButton

class LockActions(
    val onUnlockApp: () -> Unit,
    val onSubmitPin: () -> Unit,
    val onUseProfile: (OtherProfile) -> Unit,
    val onSubmitChosen: () -> Unit,
    val onDismissChoice: () -> Unit,
    val onOpenForgot: () -> Unit,
    val onCloseForgot: () -> Unit,
    val onSubmitForgot: () -> Unit,
)

/** Takes every touch, so nothing beneath the lock can be reached. */
internal fun Modifier.takesEveryTouch(): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false).consume()
        do {
            val event = awaitPointerEvent()
            event.changes.forEach { it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * The lock screen, drawn opaque over everything (the host beneath has no semantics while it shows). [unavailable]: the phone's
 * screen lock could not be used just now. At font scale 2.0 everything scrolls; every button is 48dp or more. Its window is out of
 * autofill while it shows ([NoAutofill], review I1).
 */
@Composable
internal fun LockScreen(
    state: LockUiState,
    pin: TextFieldState,
    choicePin: TextFieldState,
    password: TextFieldState,
    actions: LockActions,
    unavailable: Boolean = false,
) {
    NoAutofill()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).takesEveryTouch(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 600.dp).fillMaxWidth().safeDrawingPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
            if (state.readFailed) ErrorMessage(stringResource(R.string.lock_read_failed))
            when (val lock = state.lock) {
                LockState.App -> AppLock(actions, unavailable)
                is LockState.Profile -> ProfileLock(lock, state, pin, password, actions)
                else -> Unit
            }
        }
    }
    state.choosing?.let { p ->
        PinDialog(
            title = stringResource(R.string.switch_pin_title, p.userName),
            body = stringResource(R.string.switch_pin_body, p.userName),
            pin = choicePin,
            answer = state.pinAnswer,
            confirmLabel = stringResource(R.string.switch_pin_confirm),
            onConfirm = actions.onSubmitChosen,
            onDismiss = actions.onDismissChoice,
            failed = state.pinFailed,
            busy = state.busy,
        )
    }
}

@Composable
private fun AppLock(actions: LockActions, unavailable: Boolean) {
    Text(stringResource(R.string.lock_app_title), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(if (unavailable) R.string.lock_unavailable else R.string.lock_app_body), style = MaterialTheme.typography.bodyLarge)
    PrimaryButton(stringResource(R.string.lock_unlock), actions.onUnlockApp)
}

@Composable
private fun ProfileLock(lock: LockState.Profile, state: LockUiState, pin: TextFieldState, password: TextFieldState, actions: LockActions) {
    Text(stringResource(R.string.lock_profile_title, lock.userName), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
    if (state.forgot) {
        ForgotPinPanel(state, password, actions.onSubmitForgot, actions.onCloseForgot)
        return
    }
    Text(stringResource(R.string.lock_profile_body), style = MaterialTheme.typography.bodyLarge)
    val focus = remember { FocusRequester() }
    LaunchedEffect(lock) { runCatching { focus.requestFocus() } }
    PinField(pin, stringResource(R.string.pin_label), actions.onSubmitPin, Modifier.focusRequester(focus), enabled = !state.busy)
    if (state.choosing == null) ErrorMessage(pinAnswerText(state.pinAnswer, state.pinFailed))
    PrimaryButton(stringResource(R.string.lock_unlock), actions.onSubmitPin, loading = state.busy && state.choosing == null)
    TextButton(onClick = actions.onOpenForgot, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.lock_forgot)) }
    if (state.others.isNotEmpty()) {
        Text(stringResource(R.string.lock_other_heading), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
        state.others.forEach { OtherRow(it, actions) }
    }
}

@Composable
private fun OtherRow(p: OtherProfile, actions: LockActions) {
    val label = stringResource(R.string.lock_other_row, p.userName, p.serverName)
    val spoken = if (p.isProtected) stringResource(R.string.lock_other_row_protected_cd, p.userName, p.serverName) else label
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { actions.onUseProfile(p) }
            .semantics(mergeDescendants = true) { contentDescription = spoken }.padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (p.isProtected) ProtectedBadge()
    }
}

/** The lock icon and "Protected" beside a protected profile's name (the row says it for TalkBack). */
@Composable
internal fun ProtectedBadge(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Lock, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.lock_protected), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
