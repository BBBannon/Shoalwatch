package app.shoalwatch.ui.lock

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.shoalwatch.R
import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.ProfileSwitcher
import app.shoalwatch.data.servers.SwitchResult
import app.shoalwatch.security.PinCheck
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** A switch waiting for a protected profile's PIN. Holds no PIN. [failed]: the last try could not be saved. */
data class SwitchPrompt(val serverId: String, val userId: String, val userName: String, val answer: PinCheck? = null, val failed: Boolean = false) {
    override fun toString() = "SwitchPrompt(<redacted>)"
}

/**
 * Switching the active profile, shared by Home and Servers so both behave identically: through [switcher] only (never the
 * catalog's `setActive`). An unprotected profile switches at once; a protected one opens [prompt] and switches only after its
 * PIN. One switch at a time: a second tap while one runs does nothing. [onError] hears "no longer signed in" (`Gone`) and failures
 * of a plain switch.
 */
internal class SwitchFlow(private val switcher: ProfileSwitcher, private val scope: CoroutineScope, private val onError: (AppError) -> Unit = {}) {
    private val current = MutableStateFlow<SwitchPrompt?>(null)
    val prompt: StateFlow<SwitchPrompt?> = current.asStateFlow()

    /** Never in [SwitchPrompt]; cleared after every attempt. */
    val pin = TextFieldState()
    private val running = AtomicBoolean(false)

    fun request(serverId: String, userId: String, userName: String) {
        if (!running.compareAndSet(false, true)) return
        scope.launch {
            try {
                when (switcher.switchTo(serverId, userId)) {
                    SwitchResult.NeedsPin -> {
                        pin.clearText()
                        current.value = SwitchPrompt(serverId, userId, userName)
                    }
                    SwitchResult.Gone -> onError(AppError.of(AppError.Kind.NOT_SIGNED_IN))
                    SwitchResult.Switched, is SwitchResult.NotSwitched -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError(AppError.from(e))
            } finally {
                running.set(false)
            }
        }
    }

    fun submit() {
        val p = current.value ?: return
        if (!running.compareAndSet(false, true)) return
        val chars = pin.text.toPinArray()
        pin.clearText()
        fun same(it: SwitchPrompt?) = it != null && it.serverId == p.serverId && it.userId == p.userId
        scope.launch {
            try {
                when (val r = switcher.switchWithPin(p.serverId, p.userId, chars)) {
                    SwitchResult.Switched -> current.update { if (same(it)) null else it }
                    is SwitchResult.NotSwitched -> current.update { if (same(it)) p.copy(answer = r.check, failed = false) else it }
                    SwitchResult.Gone -> {
                        current.update { if (same(it)) null else it }
                        onError(AppError.of(AppError.Kind.NOT_SIGNED_IN))
                    }
                    SwitchResult.NeedsPin -> Unit
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The try could not be saved (a full phone): never treated as right.
                current.update { if (same(it)) p.copy(answer = null, failed = true) else it }
            } finally {
                chars.fill('\u0000')
                running.set(false)
            }
        }
    }

    fun dismiss() {
        pin.clearText()
        current.value = null
    }
}

/** The PIN dialog for [prompt] ("Switch to Anna?"). */
@Composable
internal fun SwitchPinDialog(prompt: SwitchPrompt, pin: TextFieldState, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    PinDialog(
        title = stringResource(R.string.switch_pin_title, prompt.userName),
        body = stringResource(R.string.switch_pin_body, prompt.userName),
        pin = pin,
        answer = prompt.answer,
        confirmLabel = stringResource(R.string.switch_pin_confirm),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        failed = prompt.failed,
    )
}
