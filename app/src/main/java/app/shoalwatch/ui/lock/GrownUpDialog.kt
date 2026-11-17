package app.shoalwatch.ui.lock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Session
import app.shoalwatch.security.Household
import app.shoalwatch.security.PinCheck
import app.shoalwatch.ui.components.ErrorMessage
import app.shoalwatch.ui.components.FilterObscuredTouches
import app.shoalwatch.ui.components.ObscuredTouchHint
import app.shoalwatch.ui.components.SecureDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A grown-up's PIN asked for before a household setting. [signOut]: signing out a protected profile that is not active, which
 * needs that profile's own PIN ([grownUps] is just that profile). Holds no PIN.
 */
data class GrownUpAsk(
    val grownUps: List<AccountSummary>,
    val chosen: AccountSummary?,
    val answer: PinCheck? = null,
    val failed: Boolean = false,
    val signOut: Boolean = false,
) {
    override fun toString() = "GrownUpAsk(<redacted>)"
}

/** One flow for every household setting (Download settings, Servers). The data layer refuses anyway (`HouseholdGuard`). */
internal class GrownUpFlow(private val household: Household, private val scope: CoroutineScope) {
    private val current = MutableStateFlow<GrownUpAsk?>(null)
    val ask: StateFlow<GrownUpAsk?> = current.asStateFlow()

    /** Never in [GrownUpAsk]; cleared after every attempt. */
    val pin = TextFieldState()
    private var then: (() -> Unit)? = null
    private val running = AtomicBoolean(false)

    /** Runs [then] at once if no check is needed; otherwise asks, and runs [then] after a right PIN. Dismiss ⇒ [then] never runs. */
    fun guard(then: () -> Unit) {
        if (!household.askNeeded.value) {
            then()
            return
        }
        scope.launch {
            val grownUps = household.grownUps.first()
            this@GrownUpFlow.then = then
            pin.clearText()
            current.value = GrownUpAsk(grownUps, grownUps.singleOrNull())
        }
    }

    /** Signing out [target], a protected profile that is not active: asks for [target]'s own PIN, then runs [then]. */
    fun guardSignOut(target: AccountSummary, then: () -> Unit) {
        this.then = then
        pin.clearText()
        current.value = GrownUpAsk(listOf(target), target, signOut = true)
    }

    fun choose(grownUp: AccountSummary) = current.update { it?.copy(chosen = grownUp, answer = null, failed = false) }

    fun submit() {
        val ask = current.value ?: return
        val grownUp = ask.chosen ?: return
        if (!running.compareAndSet(false, true)) return
        val chars = pin.text.toPinArray()
        pin.clearText()
        scope.launch {
            try {
                val check = household.confirm(Session(grownUp.serverId, grownUp.userId), chars)
                if (current.value !== ask) return@launch // dismissed or replaced meanwhile
                if (check == PinCheck.Correct) {
                    val next = then
                    then = null
                    current.value = null
                    next?.invoke()
                } else {
                    current.value = ask.copy(answer = check, failed = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (current.value === ask) current.value = ask.copy(answer = null, failed = true)
            } finally {
                chars.fill('\u0000')
                running.set(false)
            }
        }
    }

    fun dismiss() {
        then = null
        pin.clearText()
        current.value = null
    }
}

/** The one dialog for every household setting: a [SecureDialog] behind the obscured-touch filter. */
@Composable
internal fun GrownUpDialog(ask: GrownUpAsk, pin: TextFieldState, onChoose: (AccountSummary) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    if (ask.signOut) {
        val name = ask.chosen?.userName.orEmpty()
        PinDialog(
            title = stringResource(R.string.signout_pin_title, name),
            body = stringResource(R.string.signout_pin_body, name),
            pin = pin, answer = ask.answer, confirmLabel = stringResource(R.string.sign_out),
            onConfirm = onConfirm, onDismiss = onDismiss, failed = ask.failed,
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = SecureDialog,
        title = { Text(stringResource(R.string.grownup_title), Modifier.semantics { heading() }) },
        text = {
            val obscured = FilterObscuredTouches()
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ObscuredTouchHint(obscured)
                Text(stringResource(R.string.grownup_body))
                if (ask.grownUps.size > 1) {
                    Column(Modifier.selectableGroup()) {
                        ask.grownUps.forEach { g ->
                            val selected = g == ask.chosen
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(selected, role = Role.RadioButton) { onChoose(g) },
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RadioButton(selected = selected, onClick = null)
                                Text(g.userName)
                            }
                        }
                    }
                }
                PinField(pin, stringResource(R.string.pin_label), onConfirm, enabled = ask.chosen != null)
                ErrorMessage(pinAnswerText(ask.answer, ask.failed))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel)) }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = ask.chosen != null, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.grownup_confirm))
            }
        },
    )
}
