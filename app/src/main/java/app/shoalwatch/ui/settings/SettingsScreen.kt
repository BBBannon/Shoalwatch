package app.shoalwatch.ui.settings

import android.os.Build
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.shoalwatch.R
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.security.LockAfter
import app.shoalwatch.ui.components.AppScreen
import app.shoalwatch.ui.components.ErrorMessage
import app.shoalwatch.ui.lock.GrownUpAsk
import app.shoalwatch.ui.lock.GrownUpDialog
import app.shoalwatch.ui.lock.PinDialog

/** What the Settings screen does. [onAppLock] is the wish: the route runs the phone's own prompt before passing it on. */
class SettingsActions(
    val onBack: () -> Unit,
    val onAutoplay: (Boolean) -> Unit = {},
    val onPipAuto: (Boolean) -> Unit = {},
    val onProtect: () -> Unit = {},
    val onChangePin: () -> Unit = {},
    val onRemovePin: () -> Unit = {},
    val onSubmitPin: () -> Unit = {},
    val onCancelPin: () -> Unit = {},
    val onAppLock: (Boolean) -> Unit = {},
    val onLockAfter: (LockAfter) -> Unit = {},
    val onHideInRecents: (Boolean) -> Unit = {},
    val onDownloadSettings: () -> Unit = {},
    val onChooseGrownUp: (AccountSummary) -> Unit = {},
    val onSubmitGrownUp: () -> Unit = {},
    val onDismissGrownUp: () -> Unit = {},
)

/**
 * Settings (Milestone 7): Playback (this profile's autoplay; picture-in-picture on leaving, for the phone), This profile (its
 * PIN), App lock ("Lock after" too), Privacy (hide in recent apps) and a link to Download settings. Every group is a heading, every row 48dp or more and says its state; the screen
 * scrolls (`AppScreen`). The PIN steps and the grown-up check are secure dialogs. [sdkInt]: Android 10–12 has only `FLAG_SECURE`
 * to hide the recents preview, which also stops screenshots, and the help says so.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    pin: TextFieldState,
    grownUp: GrownUpAsk? = null,
    grownUpPin: TextFieldState? = null,
    sdkInt: Int = Build.VERSION.SDK_INT,
) {
    if (grownUp != null && grownUpPin != null) {
        GrownUpDialog(grownUp, grownUpPin, actions.onChooseGrownUp, actions.onSubmitGrownUp, actions.onDismissGrownUp)
    }
    state.pinStep?.let { PinStepDialog(state, it, pin, actions) }
    AppScreen(title = stringResource(R.string.settings_title), onBack = actions.onBack) {
        if (state.householdRace) ErrorMessage(stringResource(R.string.grownup_race))
        if (state.notSaved) ErrorMessage(stringResource(R.string.settings_not_saved))
        SettingsHeading(stringResource(R.string.settings_playback))
        if (state.session != null) {
            SettingsSwitch(stringResource(R.string.settings_autoplay), stringResource(R.string.settings_autoplay_help), state.autoplay, actions.onAutoplay)
        }
        SettingsSwitch(stringResource(R.string.settings_pip), stringResource(R.string.settings_pip_help), state.pipAuto, actions.onPipAuto)
        if (state.session != null) {
            SettingsHeading(stringResource(R.string.settings_profile, state.userName.orEmpty()))
            if (state.profileProtected) {
                Text(stringResource(R.string.settings_protected), style = MaterialTheme.typography.bodyLarge)
                SettingsButton(stringResource(R.string.settings_change_pin), actions.onChangePin)
                SettingsButton(stringResource(R.string.settings_remove_pin), actions.onRemovePin)
            } else {
                SettingsButton(stringResource(R.string.settings_protect), actions.onProtect)
                SettingsHelp(stringResource(R.string.settings_protect_help))
            }
        }
        SettingsHeading(stringResource(R.string.settings_app_lock_heading))
        SettingsSwitch(
            stringResource(R.string.settings_app_lock),
            if (state.deviceSecure) null else stringResource(R.string.settings_app_lock_no_screen_lock),
            state.lock.appLock && state.deviceSecure, actions.onAppLock, enabled = state.deviceSecure,
        )
        SettingsHeading(stringResource(R.string.settings_lock_after))
        SettingsRadios(LOCK_AFTER.map { (value, label) -> value to stringResource(label) }, state.lock.lockAfter, actions.onLockAfter)
        SettingsHelp(stringResource(R.string.settings_lock_after_help))
        SettingsHeading(stringResource(R.string.settings_privacy))
        val oldAndroid = if (sdkInt < Build.VERSION_CODES.TIRAMISU) stringResource(R.string.settings_hide_recents_old_android) else null
        val help = if (state.recentsForced) listOfNotNull(stringResource(R.string.settings_hide_recents_forced), oldAndroid).joinToString(" ") else oldAndroid
        SettingsSwitch(
            stringResource(R.string.settings_hide_recents), help, state.recentsForced || state.lock.hideInRecents, actions.onHideInRecents,
            enabled = !state.recentsForced,
        )
        SettingsHeading(stringResource(R.string.nav_downloads))
        SettingsButton(stringResource(R.string.settings_downloads), actions.onDownloadSettings)
    }
}

private val LOCK_AFTER = listOf(
    LockAfter.IMMEDIATELY to R.string.lock_after_immediately, LockAfter.ONE_MINUTE to R.string.lock_after_1,
    LockAfter.FIVE_MINUTES to R.string.lock_after_5, LockAfter.FIFTEEN_MINUTES to R.string.lock_after_15,
)

/** One PIN step in a secure dialog; the field is cleared after each step by the ViewModel. */
@Composable
private fun PinStepDialog(state: SettingsUiState, step: PinStep, pin: TextFieldState, actions: SettingsActions) {
    val title = when (step) {
        PinStep.NEW, PinStep.NEW_FOR_CHANGE -> R.string.settings_pin_choose
        PinStep.REPEAT, PinStep.REPEAT_FOR_CHANGE -> R.string.settings_pin_repeat
        PinStep.CURRENT_FOR_CHANGE, PinStep.CURRENT_FOR_REMOVE -> R.string.settings_pin_current
    }
    val body = when (step) {
        PinStep.CURRENT_FOR_CHANGE, PinStep.CURRENT_FOR_REMOVE -> R.string.lock_profile_body
        else -> R.string.pin_rules
    }
    val confirm = when (step) {
        PinStep.NEW, PinStep.NEW_FOR_CHANGE, PinStep.CURRENT_FOR_CHANGE -> R.string.action_continue
        PinStep.REPEAT, PinStep.REPEAT_FOR_CHANGE -> R.string.action_save
        PinStep.CURRENT_FOR_REMOVE -> R.string.settings_remove_pin
    }
    val note = when {
        state.pinMismatch -> stringResource(R.string.settings_pin_mismatch)
        state.pinTooShort -> stringResource(R.string.pin_rules)
        else -> null
    }
    PinDialog(
        title = stringResource(title), body = stringResource(body), pin = pin, answer = state.pinAnswer,
        confirmLabel = stringResource(confirm), onConfirm = actions.onSubmitPin, onDismiss = actions.onCancelPin,
        failed = state.pinFailed, note = note,
    )
}
