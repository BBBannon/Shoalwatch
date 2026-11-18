package app.shoalwatch.ui.lock

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.Authenticator
import app.shoalwatch.data.servers.ProfileSwitcher
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.servers.SwitchResult
import app.shoalwatch.security.ClearProof
import app.shoalwatch.security.ForgotThrottle
import app.shoalwatch.security.LockGate
import app.shoalwatch.security.LockState
import app.shoalwatch.security.PinCheck
import app.shoalwatch.security.retryingReads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Another usable profile on this phone, for "Use another profile". */
data class OtherProfile(val serverId: String, val userId: String, val userName: String, val serverName: String, val isProtected: Boolean) {
    override fun toString() = "OtherProfile(<redacted>)"
}

data class LockUiState(
    val lock: LockState = LockState.Checking,
    val others: List<OtherProfile> = emptyList(),
    /** The last PIN answer to show (Wrong / Wait / Unreadable), cleared on typing. */
    val pinAnswer: PinCheck? = null,
    /** The last try could not be saved, so nothing was checked (a full phone): a plain error, "Forgot PIN" still offered. */
    val pinFailed: Boolean = false,
    val busy: Boolean = false,
    /** "Forgot PIN" is open: the password panel for the locked profile. */
    val forgot: Boolean = false,
    val forgotError: AppError.Kind? = null,
    /** The password was accepted for another account: nothing was cleared. */
    val forgotOtherAccount: Boolean = false,
    /** The locked profile's server name, for the "Forgot PIN" words. */
    val lockedServerName: String? = null,
    /** "Forgot PIN" must wait this many seconds before the next try (review M5), or null. */
    val forgotWait: Int? = null,
    /** The gate could not read the saved locks just now (review M6): the lock stays, with a plain error. */
    val readFailed: Boolean = false,
    /** A protected profile chosen under "Use another profile": its name, for the PIN dialog. */
    val choosing: OtherProfile? = null,
) {
    override fun toString() = "LockUiState(<redacted>)"
}

/**
 * The lock screen (Milestone 7 Task 5). The PIN and the password live only in [pin], [choicePin] and [password], are copied into
 * arrays that the checks zero, and the fields are cleared after every attempt and whenever the lock changes. "Forgot PIN" signs in
 * with the locked profile's own server password; only a [ClearProof] made from that answer reaches [clearProtection].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LockViewModel(
    private val gate: LockGate,
    private val switcher: ProfileSwitcher,
    private val catalog: ServerCatalog,
    private val authenticator: Authenticator,
    /** The profiles that are protected (for the "Protected" marks). */
    protectedProfiles: Flow<Set<Session>>,
    private val forgotThrottle: ForgotThrottle,
    private val clearProtection: suspend (ClearProof) -> Unit,
) : ViewModel() {
    private data class Local(
        val pinAnswer: PinCheck? = null, val pinFailed: Boolean = false, val busy: Boolean = false, val forgot: Boolean = false,
        val forgotError: AppError.Kind? = null, val forgotOtherAccount: Boolean = false, val choosing: OtherProfile? = null,
        val forgotWait: Int? = null,
    )

    private val local = MutableStateFlow(Local())

    /** Never in [LockUiState]; cleared after every attempt and when the lock changes. */
    val pin = TextFieldState()

    /** The PIN for a protected profile chosen under "Use another profile". */
    val choicePin = TextFieldState()
    val password = TextFieldState()

    private val everyone: Flow<List<OtherProfile>> = catalog.servers.flatMapLatest { servers ->
        if (servers.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(servers.map { s -> catalog.accounts(s.id).map { list -> list.map { it to s.name } } }) { it.toList().flatten() }
                .combine(protectedProfiles.retryingReads()) { list, set ->
                    list.map { (a, server) -> OtherProfile(a.serverId, a.userId, a.userName, server, Session(a.serverId, a.userId) in set) }
                }
        }
    }

    val state: StateFlow<LockUiState> = combine(gate.state, everyone, catalog.servers, local, gate.readFailed) { lock, all, servers, l, failed ->
        val locked = (lock as? LockState.Profile)?.session
        LockUiState(
            lock = lock,
            others = all.filter { Session(it.serverId, it.userId) != locked },
            pinAnswer = l.pinAnswer, pinFailed = l.pinFailed, busy = l.busy, forgot = l.forgot, forgotError = l.forgotError,
            forgotOtherAccount = l.forgotOtherAccount, lockedServerName = servers.firstOrNull { it.id == locked?.serverId }?.name,
            choosing = l.choosing, forgotWait = l.forgotWait, readFailed = failed,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LockUiState())

    init {
        // A new lock (another profile, the app lock, open again) starts clean: no field keeps what was typed for the old one.
        viewModelScope.launch {
            gate.state.drop(1).collect {
                clearFields()
                local.value = Local()
            }
        }
    }

    fun submitPin() {
        if (local.value.busy || gate.state.value !is LockState.Profile) return
        val chars = pin.text.toPinArray()
        pin.clearText()
        attempt(chars) {
            val answer = gate.unlockProfile(chars)
            local.update { it.copy(pinAnswer = answer.takeUnless { a -> a == PinCheck.Correct || a == PinCheck.NotProtected }, pinFailed = false) }
        }
    }

    fun useProfile(p: OtherProfile) {
        if (local.value.busy) return
        choicePin.clearText()
        if (p.isProtected) {
            local.update { it.copy(choosing = p, pinAnswer = null, pinFailed = false) }
            return
        }
        attempt(null) {
            // Unprotected: the gate sees an unprotected profile become active and opens.
            if (switcher.switchTo(p.serverId, p.userId) == SwitchResult.NeedsPin) {
                local.update { it.copy(choosing = p.copy(isProtected = true), pinAnswer = null) }
            }
        }
    }

    fun submitChosenPin() {
        val p = local.value.choosing ?: return
        if (local.value.busy) return
        val chars = choicePin.text.toPinArray()
        choicePin.clearText()
        attempt(chars) {
            when (val r = switcher.switchWithPin(p.serverId, p.userId, chars)) {
                SwitchResult.Switched, SwitchResult.Gone -> local.update { it.copy(choosing = null, pinAnswer = null, pinFailed = false) }
                is SwitchResult.NotSwitched -> local.update { it.copy(pinAnswer = r.check, pinFailed = false) }
                SwitchResult.NeedsPin -> Unit
            }
        }
    }

    fun dismissChoice() {
        choicePin.clearText()
        local.update { it.copy(choosing = null, pinAnswer = null, pinFailed = false) }
    }

    fun openForgot() {
        password.clearText()
        local.update { it.copy(forgot = true, forgotError = null, forgotOtherAccount = false, forgotWait = null) }
    }

    fun closeForgot() {
        password.clearText()
        local.update { it.copy(forgot = false, forgotError = null, forgotOtherAccount = false, forgotWait = null) }
    }

    /**
     * Quick Connect is not offered here: an approval from another device is not proof the account is yours. While [ForgotThrottle]
     * says wait, nothing is sent (review M5); a wrong password or another account's starts the next wait.
     */
    fun submitForgot() {
        val locked = gate.state.value as? LockState.Profile ?: return
        if (local.value.busy) return
        val chars = password.text.toPinArray()
        password.clearText()
        val wait = forgotThrottle.waitSeconds(locked.session)
        if (wait > 0) {
            chars.fill('\u0000')
            local.update { it.copy(forgotWait = wait, forgotError = null, forgotOtherAccount = false) }
            return
        }
        local.update { it.copy(busy = true, forgotError = null, forgotOtherAccount = false, forgotWait = null) }
        viewModelScope.launch {
            try {
                val route = authenticator.signInRoute(locked.session.serverId)
                if (route == null) {
                    local.update { it.copy(forgotError = AppError.Kind.OFFLINE) }
                    return@launch
                }
                val account = authenticator.signInWithPassword(route, locked.userName, chars)
                val proof = ClearProof.afterPasswordSignIn(locked.session, account)
                if (proof == null) {
                    forgotThrottle.failed(locked.session)
                    local.update { it.copy(forgotOtherAccount = true, forgotWait = forgotThrottle.waitSeconds(locked.session)) }
                } else {
                    forgotThrottle.succeeded(locked.session)
                    clearProtection(proof)
                    gate.profileCleared(locked.session)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val kind = AppError.from(e).kind
                if (kind == AppError.Kind.UNAUTHORISED) {
                    forgotThrottle.failed(locked.session)
                    local.update { it.copy(forgotWait = forgotThrottle.waitSeconds(locked.session)) }
                }
                local.update { it.copy(forgotError = kind) }
            } finally {
                chars.fill('\u0000')
                local.update { it.copy(busy = false) }
            }
        }
    }

    /** Runs [block] as one attempt; a try that could not be saved (an `AppError` from the check) is a plain error, never right. */
    private fun attempt(chars: CharArray?, block: suspend () -> Unit) {
        local.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                local.update { it.copy(pinFailed = true, pinAnswer = null) }
            } finally {
                chars?.fill('\u0000')
                local.update { it.copy(busy = false) }
            }
        }
    }

    private fun clearFields() {
        pin.clearText()
        choicePin.clearText()
        password.clearText()
    }

    override fun onCleared() = clearFields()

    override fun toString() = "LockViewModel(<redacted>)"
}
