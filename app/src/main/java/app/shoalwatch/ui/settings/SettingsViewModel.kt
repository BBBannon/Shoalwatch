package app.shoalwatch.ui.settings

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.data.playback.PipPrefs
import app.shoalwatch.data.playback.ProfilePlayback
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.Session
import app.shoalwatch.security.Household
import app.shoalwatch.security.HouseholdLockedException
import app.shoalwatch.security.LockAfter
import app.shoalwatch.security.LockPrefs
import app.shoalwatch.security.LockSettings
import app.shoalwatch.security.PinCheck
import app.shoalwatch.security.PinRules
import app.shoalwatch.security.ProfileProtection
import app.shoalwatch.security.retryingReads
import app.shoalwatch.ui.lock.GrownUpFlow
import app.shoalwatch.ui.lock.toPinArray
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class SettingsUiState(
    val session: Session? = null,
    val userName: String? = null,
    val autoplay: Boolean = true,
    /** "Keep playing in a small window when you leave the app": a device setting, not this profile's. */
    val pipAuto: Boolean = true,
    val profileProtected: Boolean = false,
    val lock: LockSettings = LockSettings(),
    /** The phone has a secure screen lock (the app lock row is off and explained without one). */
    val deviceSecure: Boolean = false,
    /** Hidden in recents because of the app lock or a protected profile, whatever the switch says. */
    val recentsForced: Boolean = false,
    val pinStep: PinStep? = null,
    /** The answer to the last current PIN typed (wrong, a wait …); never the PIN. */
    val pinAnswer: PinCheck? = null,
    /** The last try could not be saved, so nothing was checked (never shown as right). */
    val pinFailed: Boolean = false,
    /** "Those PINs don't match. Try again." on the step that follows. */
    val pinMismatch: Boolean = false,
    /** "Use 4 to 12 digits." */
    val pinTooShort: Boolean = false,
    /** The data layer refused (the grown-up's pass ran out meanwhile): "A grown-up's PIN is needed. Try again." */
    val householdRace: Boolean = false,
    /** A change was not saved (autoplay's write failed, or the PIN was not set): "That change wasn't saved. Try again." */
    val notSaved: Boolean = false,
) {
    override fun toString() = "SettingsUiState(<redacted>)"
}

enum class PinStep { NEW, REPEAT, CURRENT_FOR_CHANGE, NEW_FOR_CHANGE, REPEAT_FOR_CHANGE, CURRENT_FOR_REMOVE }

/** What the steps add to the stored state: everything but the PINs, which are only in [SettingsViewModel.pin] and two arrays. */
private data class Steps(
    val step: PinStep? = null,
    val answer: PinCheck? = null,
    val failed: Boolean = false,
    val mismatch: Boolean = false,
    val tooShort: Boolean = false,
    val race: Boolean = false,
    val notSaved: Boolean = false,
)

/**
 * Settings: the ACTIVE profile's autoplay and its own PIN, and the phone's app lock settings. Protecting, changing or removing a
 * PIN, the app lock, "Lock after" and "Hide in recent apps" go through [grownUp] first (it asks only while a protected profile
 * exists and the active one is not, so the first PIN on a phone is free; review I1 of Tasks 3-4). PINs live only in [pin] and,
 * between steps, in two arrays zeroed when the steps end, the session changes or the screen goes; never in [state].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(
    catalog: ServerCatalog,
    private val playback: ProfilePlayback,
    private val protection: ProfileProtection,
    private val prefs: LockPrefs,
    household: Household,
    private val deviceSecure: () -> Boolean,
    hideRecents: StateFlow<Boolean>,
    private val pip: PipPrefs? = null,
) : ViewModel() {
    val pin = TextFieldState()
    internal val grownUp = GrownUpFlow(household, viewModelScope)

    private val steps = MutableStateFlow(Steps())
    private val secure = MutableStateFlow(deviceSecure())
    private val running = AtomicBoolean(false)
    private var newPin: CharArray? = null
    private var currentPin: CharArray? = null

    /** Moves on at every end of the steps (Cancel, a session change …): an answer for an earlier run is dropped (review M1 of Tasks 7-8). */
    private var generation = 0

    private val active = catalog.activeAccount.map { a -> a?.let { Session(it.serverId, it.userId) to it.userName } }.distinctUntilChanged()

    private val profile = active.flatMapLatest { pair ->
        val s = pair?.first ?: return@flatMapLatest flowOf(Triple(null, true, false))
        combine(playback.autoplay(s).retryingReads(), protection.isProtectedFlow(s).retryingReads()) { auto, prot -> Triple(pair, auto, prot) }
    }

    private val device = combine(prefs.settings, secure, hideRecents, protection.protectedProfiles.retryingReads()) { lock, sec, hide, set ->
        // Forced unless the switch alone hides it; with the switch off, whatever the gate says (true until it knows).
        Triple(lock, sec, hide && (lock.appLock || set.isNotEmpty() || !lock.hideInRecents))
    }

    val state: StateFlow<SettingsUiState> = combine(profile, device, steps, pip?.autoEnter ?: flowOf(true)) { (who, auto, prot), (lock, sec, forced), st, pipAuto ->
        SettingsUiState(
            who?.first, who?.second, auto, pipAuto, prot, lock, sec, forced,
            st.step, st.answer, st.failed, st.mismatch, st.tooShort, st.race, st.notSaved,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState(deviceSecure = secure.value))

    init {
        viewModelScope.launch {
            var first = true
            active.map { it?.first }.distinctUntilChanged().collect {
                if (!first) {
                    endSteps(Steps())
                    grownUp.dismiss()
                }
                first = false
            }
        }
    }

    /** Picture-in-picture on leaving the app: a device setting with no grown-up check (it shows nobody else's anything). */
    fun setPipAuto(on: Boolean) { pip?.setAutoEnter(on) }

    /** For the active profile only, as itself (no grown-up check: it changes nothing for anyone else). */
    fun setAutoplay(on: Boolean) {
        val s = state.value.session ?: return
        viewModelScope.launch {
            try {
                playback.setAutoplay(s, on)
                steps.update { it.copy(notSaved = false) } // a later save that worked clears the line (re-review nit)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                steps.update { it.copy(notSaved = true) } // the switch shows what is stored (review M4 of Tasks 7-8)
            }
        }
    }

    fun startProtect() = startSteps(PinStep.NEW) { !it.profileProtected }
    fun startChangePin() = startSteps(PinStep.CURRENT_FOR_CHANGE) { it.profileProtected }
    fun startRemovePin() = startSteps(PinStep.CURRENT_FOR_REMOVE) { it.profileProtected }

    fun submitPin() {
        val s = state.value.session ?: return
        val step = steps.value.step ?: return
        if (!running.compareAndSet(false, true)) return
        val typed = pin.text.toPinArray()
        pin.clearText()
        val run = generation
        viewModelScope.launch {
            try {
                submit(s, step, typed, run)
            } catch (e: CancellationException) {
                throw e
            } catch (e: HouseholdLockedException) {
                if (run == generation) endSteps(Steps(race = true))
            } catch (e: Exception) {
                if (run == generation) endSteps(Steps(step = firstOf(step), failed = true))
            } finally {
                typed.fill('\u0000')
                running.set(false)
            }
        }
    }

    fun cancelPin() = endSteps(Steps())

    /** After the system prompt said yes (the screen runs it, as the lock screen does). Grown-up check first. */
    fun setAppLock(on: Boolean) {
        if (on && !deviceSecure()) return
        grownUp.guard { save { prefs.setAppLock(on) } }
    }

    fun setLockAfter(value: LockAfter) = grownUp.guard { save { prefs.setLockAfter(value) } }

    fun setHideInRecents(on: Boolean) {
        if (state.value.recentsForced) return
        grownUp.guard { save { prefs.setHideInRecents(on) } }
    }

    /** A lock setting, written in this screen's scope (the store asks the household check again: review I1 of Tasks 7-8). */
    private fun save(write: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                write()
                steps.update { it.copy(notSaved = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HouseholdLockedException) {
                steps.update { it.copy(race = true) }
            } catch (_: Exception) { // the store's household check could not read (re-review N2): never a crash
                steps.update { it.copy(notSaved = true) }
            }
        }
    }

    /** The phone's screen lock may have been set or removed while the app was away. */
    fun refreshDevice() {
        secure.value = deviceSecure()
    }

    override fun onCleared() {
        endSteps(Steps())
        grownUp.dismiss()
    }

    /** Opens the first step for the profile active now, after the grown-up check; nothing if the session changes meanwhile. */
    private fun startSteps(first: PinStep, allowed: (SettingsUiState) -> Boolean) {
        val now = state.value
        val s = now.session ?: return
        if (!allowed(now)) return
        steps.update { it.copy(race = false) }
        grownUp.guard {
            if (state.value.session == s) {
                endSteps(Steps(step = first))
            }
        }
    }

    /**
     * [typed] is zeroed by the caller (and by [protection], which zeroes whatever it is given). After each call that can wait
     * (a counted check), nothing is shown unless the steps are still [run]: Cancel during the check stays cancelled.
     */
    private suspend fun submit(s: Session, step: PinStep, typed: CharArray, run: Int) {
        when (step) {
            PinStep.NEW, PinStep.NEW_FOR_CHANGE -> {
                if (!PinRules.valid(typed)) {
                    steps.value = Steps(step = step, tooShort = true)
                    return
                }
                zero(newPin)
                newPin = typed.copyOf()
                steps.value = Steps(step = if (step == PinStep.NEW) PinStep.REPEAT else PinStep.REPEAT_FOR_CHANGE)
            }
            PinStep.REPEAT, PinStep.REPEAT_FOR_CHANGE -> {
                val chosen = newPin
                if (chosen == null || !chosen.contentEquals(typed)) {
                    zero(newPin)
                    newPin = null
                    steps.value = Steps(step = if (step == PinStep.REPEAT) PinStep.NEW else PinStep.NEW_FOR_CHANGE, mismatch = true)
                    return
                }
                newPin = null
                if (step == PinStep.REPEAT) {
                    val saved = protection.protect(s, chosen)
                    if (run == generation) endSteps(Steps(notSaved = !saved)) // false: protected meanwhile (review M4)
                } else {
                    val old = currentPin
                    currentPin = null
                    if (old == null) {
                        zero(chosen)
                        return endSteps(Steps())
                    }
                    val check = protection.changePin(s, old, chosen)
                    if (run != generation) return
                    when (check) {
                        PinCheck.Correct, PinCheck.NotProtected -> endSteps(Steps())
                        else -> endSteps(Steps(step = PinStep.CURRENT_FOR_CHANGE, answer = check))
                    }
                }
            }
            PinStep.CURRENT_FOR_CHANGE -> {
                // Kept for "Change PIN" itself, which checks it again: a right PIN now only opens the next step.
                val kept = typed.copyOf()
                val check = try {
                    protection.check(s, typed)
                } catch (e: Throwable) {
                    zero(kept)
                    throw e
                }
                if (run != generation) {
                    zero(kept)
                    return
                }
                when (check) {
                    PinCheck.Correct -> {
                        zero(currentPin)
                        currentPin = kept
                        steps.value = Steps(step = PinStep.NEW_FOR_CHANGE)
                    }
                    PinCheck.NotProtected -> { zero(kept); endSteps(Steps()) }
                    else -> { zero(kept); steps.value = Steps(step = step, answer = check) }
                }
            }
            PinStep.CURRENT_FOR_REMOVE -> {
                val check = protection.unprotect(s, typed)
                if (run != generation) return
                when (check) {
                    PinCheck.Correct, PinCheck.NotProtected -> endSteps(Steps())
                    else -> steps.value = Steps(step = step, answer = check)
                }
            }
        }
    }

    private fun firstOf(step: PinStep): PinStep = when (step) {
        PinStep.NEW, PinStep.REPEAT -> PinStep.NEW
        PinStep.CURRENT_FOR_CHANGE, PinStep.NEW_FOR_CHANGE, PinStep.REPEAT_FOR_CHANGE -> PinStep.CURRENT_FOR_CHANGE
        PinStep.CURRENT_FOR_REMOVE -> PinStep.CURRENT_FOR_REMOVE
    }

    /** Every held PIN is zeroed and the field cleared, whatever comes next. */
    private fun endSteps(next: Steps) {
        generation++
        zero(newPin)
        zero(currentPin)
        newPin = null
        currentPin = null
        pin.clearText()
        steps.value = next
    }

    private fun zero(chars: CharArray?) = chars?.fill('\u0000')

    override fun toString() = "SettingsViewModel(<redacted>)"
}
