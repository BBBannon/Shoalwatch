package app.shoalwatch.security

import app.shoalwatch.core.MonotonicClock
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ProvedSwitches
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

sealed interface LockState {
    /** Start-up, until the settings, the active profile and its protection are known: the splash shows. */
    data object Checking : LockState
    data object Open : LockState

    /** The phone's own screen lock is asked for ([DeviceAuth]). */
    data object App : LockState

    /** [session]'s PIN is asked for. [userName] is shown ("Ben's profile is protected"). */
    data class Profile(val session: Session, val userName: String) : LockState {
        override fun toString() = "Profile(<redacted>)"
    }
}

/**
 * The one lock state for the app (Milestone 7; see "App lock" in docs/ARCHITECTURE.md). The rules:
 * 1. Start, and a return after [LockSettings.lockAfter]: the app lock (when on and the phone is secure) first, then the active
 *    profile's PIN when it is protected, else open.
 * 2. Leaving is timed by [clock] (monotonic) only; [expectReturn] makes the next trip not count.
 * 3. Whenever the active profile changes to a protected one, it is locked unless [ProvedSwitches.consume] says its PIN was just
 *    typed for that switch: this catches every path that makes a profile active (a sign-in, "Sign in again", a restore), not only
 *    the switcher. A change to an unprotected profile, or to nobody, opens a profile lock.
 * 4. After the app lock, the profile lock. 5. A phone with no secure screen lock cannot keep the app lock ([appLockOff]).
 * 6. Every move into a lock ends the household pass. 7. [hideRecents] while any lock could apply.
 */
class LockGate(
    private val settings: StateFlow<LockSettings>,
    private val locks: ProfileLocks,
    private val catalog: ServerCatalog,
    private val proved: ProvedSwitches,
    private val household: HouseholdGuard,
    private val deviceSecure: () -> Boolean,
    private val clock: MonotonicClock,
    private val scope: CoroutineScope,
    /** Turns the app lock off in [LockPrefs] (rule 5). */
    private val appLockOff: () -> Unit,
) {
    private val mutableState = MutableStateFlow<LockState>(LockState.Checking)
    val state: StateFlow<LockState> = mutableState.asStateFlow()

    private val hide = MutableStateFlow(true) // hidden until the settings and the protected profiles are known

    /** True while the recents preview must be hidden: hideInRecents, or appLock, or any protected profile. */
    val hideRecents: StateFlow<Boolean> = hide.asStateFlow()

    private val lock = Any()
    private var started = false // the rest guarded by lock
    private var known = false
    private var active: AccountSummary? = null
    private var protectedSet: Set<Session> = emptySet()
    private var leftAt: Long? = null
    private var expectedAt: Long? = null
    private var ignoringTrip = false

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        scope.launch {
            combine(catalog.activeAccount, locks.protectedProfiles, settings) { a, p, s -> Triple(a, p, s) }.collect { (a, p, s) -> step(a, p, s) }
        }
    }

    private suspend fun step(a: AccountSummary?, p: Set<Session>, s: LockSettings) {
        val now = a?.session()
        val changed = synchronized(lock) {
            protectedSet = p
            hide.value = s.hideInRecents || s.appLock || p.isNotEmpty()
            if (!known) {
                known = true
                active = a
                relock()
                return
            }
            val before = active?.session()
            active = a
            now != before
        }
        if (changed) {
            // The store's own answer as well as the observed set, so a profile protected a moment ago is never missed.
            val isProtected = now != null && (now in p || locks.isProtected(now))
            val wasProved = isProtected && proved.consume(now!!)
            synchronized(lock) {
                val current = mutableState.value
                when {
                    active?.session() != now -> Unit // a newer change has arrived; its own step decides
                    !isProtected -> if (current is LockState.Profile) mutableState.value = LockState.Open
                    current == LockState.App || current == LockState.Checking -> Unit // rule 4 asks for the profile next
                    wasProved -> mutableState.value = LockState.Open
                    else -> moveTo(LockState.Profile(now!!, a!!.userName))
                }
            }
        }
        synchronized(lock) { if (!s.appLock && mutableState.value == LockState.App) relock() }
    }

    /** The activity stopped (not a configuration change), or entered picture-in-picture. The earliest time is kept. */
    fun onLeft() = synchronized(lock) {
        val now = clock.nowMs()
        val expected = expectedAt
        expectedAt = null
        if (expected != null && now - expected <= EXPECT_WINDOW_MS) {
            ignoringTrip = true
        } else if (!ignoringTrip && leftAt == null) {
            leftAt = now
        }
    }

    /** The activity started again, or left picture-in-picture for the full screen. */
    fun onReturned() = synchronized(lock) {
        if (ignoringTrip) {
            ignoringTrip = false
            return@synchronized
        }
        val left = leftAt ?: return@synchronized
        leftAt = null
        if (known && clock.nowMs() - left >= settings.value.lockAfter.ms) relock()
    }

    /**
     * Before the app opens its own system screen (the unlock prompt, the client-certificate chooser): that trip is not "leaving".
     * It must start within [EXPECT_WINDOW_MS], so a screen that never stops the activity cannot leave the next real trip uncounted.
     */
    fun expectReturn() = synchronized(lock) { expectedAt = clock.nowMs() }

    /** [DeviceAuth] said yes. */
    fun appUnlocked() = synchronized(lock) {
        if (mutableState.value == LockState.App) profileOrOpen()
    }

    /** Zeroes [pin]. Correct ⇒ Open. */
    suspend fun unlockProfile(pin: CharArray): PinCheck {
        val target = (state.value as? LockState.Profile)?.session
        if (target == null) {
            pin.fill('\u0000')
            return PinCheck.NotProtected
        }
        val check = locks.check(target, pin)
        if (check == PinCheck.Correct || check == PinCheck.NotProtected) openIfLocked(target)
        return check
    }

    /** After "Forgot PIN" cleared the profile's protection (Task 5). Opens only once the store agrees it is no longer protected. */
    fun profileCleared(s: Session) {
        scope.launch { if (!locks.isProtected(s)) openIfLocked(s) }
    }

    private fun openIfLocked(s: Session) = synchronized(lock) {
        if ((mutableState.value as? LockState.Profile)?.session == s) mutableState.value = LockState.Open
    }

    /** Rule 1. Called holding [lock]. */
    private fun relock() {
        if (settings.value.appLock) {
            if (deviceSecure()) {
                moveTo(LockState.App)
                return
            }
            appLockOff()
        }
        profileOrOpen()
    }

    /** Called holding [lock]. */
    private fun profileOrOpen() {
        val a = active
        if (a != null && a.session() in protectedSet) moveTo(LockState.Profile(a.session(), a.userName)) else mutableState.value = LockState.Open
    }

    /** Called holding [lock]. */
    private fun moveTo(locked: LockState) {
        mutableState.value = locked
        household.endPass()
    }

    private fun AccountSummary.session() = Session(serverId, userId)

    override fun toString() = "LockGate(<redacted>)"

    companion object {
        /** How soon after [expectReturn] the activity must stop for that trip not to count. */
        const val EXPECT_WINDOW_MS = 10_000L
    }
}
