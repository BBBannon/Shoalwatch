package app.shoalwatch.security

import app.shoalwatch.core.MonotonicClock
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ProvedSwitches
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.retryWhen
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

/** Leaving the app and coming back, as the activity tells it: [LockGate] (a recording fake in tests). */
interface AppTrips {
    fun onLeft()
    fun onReturned()
}

/** A session the gate has judged and found open ([session] null = nobody active). */
data class Judged(val session: Session?) {
    override fun toString() = "Judged(<redacted>)"
}

/**
 * The one lock state for the app (Milestone 7; see "App lock" in docs/ARCHITECTURE.md). The rules:
 * 1. Start, and a return after [LockSettings.lockAfter]: the app lock (when on and the phone is secure) first, then the active
 *    profile's PIN when it is protected, else open.
 * 2. Leaving is timed by [clock] (monotonic) only, and only while nothing plays ([setPlaying]: a video playing in the
 *    picture-in-picture window holds the clock; its paused time adds up across plays within one trip, see [AwayTime]);
 *    [expectReturn] makes the next trip not count (it must start within [EXPECT_WINDOW_MS] and before [tripDone], and is still
 *    capped at max(Lock after, [EXCUSED_CAP_MS])).
 * 3. Whenever the active profile changes to a protected one, it is locked unless [ProvedSwitches.consume] says its PIN was just
 *    typed for that switch: this catches every path that makes a profile active (a sign-in, "Sign in again", a restore), not only
 *    the switcher. A change to an unprotected profile, or to nobody, opens a profile lock. The lock is decided at once, from the
 *    protected set already in hand (no read first); the store's own answer is asked afterwards and can only add a lock.
 * 4. After the app lock, the profile lock. 5. A phone with no secure screen lock cannot keep the app lock ([appLockOff]).
 * 6. Every move into a lock ends the household pass. 7. [hideRecents] while any lock could apply.
 *
 * [openFor] is the session the gate has judged and found open. The active profile can change faster than the gate hears of it
 * (a flow can skip a value), so screens and the household check treat any active session other than [openFor] as locked
 * (review I3): nothing of a profile shows until the gate has judged that very profile.
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
) : AppTrips {
    private val mutableState = MutableStateFlow<LockState>(LockState.Checking)
    val state: StateFlow<LockState> = mutableState.asStateFlow()

    private val judged = MutableStateFlow<Judged?>(null)

    /** The active session once the gate has judged it and found it open; null while any lock applies or a judgement is pending. */
    val openFor: StateFlow<Judged?> = judged.asStateFlow()

    private val failedRead = MutableStateFlow(false)

    /** True while the gate's last read of the saved locks failed (review M6): the lock stays as it is and the lock screen says so. */
    val readFailed: StateFlow<Boolean> = failedRead.asStateFlow()

    private val hide = MutableStateFlow(true) // hidden until the settings and the protected profiles are known

    /** True while the recents preview must be hidden: hideInRecents, or appLock, or any protected profile. */
    val hideRecents: StateFlow<Boolean> = hide.asStateFlow()

    private val lock = Any()
    private var started = false // the rest guarded by lock
    private var known = false
    private var active: AccountSummary? = null
    private var protectedSet: Set<Session> = emptySet()
    private val away = AwayTime()
    private var expectedAt: Long? = null
    private var ignoringTrip = false
    private var excusedAt = 0L
    private var rechecking: Session? = null // a change the store is still being asked about: not judged open yet

    fun start() {
        synchronized(lock) {
            if (started) return
            started = true
        }
        scope.launch {
            // A failed read (review M6) never crashes the app and never opens anything: the lock stays as it is, the lock screen
            // says so, and the read starts again. A profile change meanwhile is not judged, so `openFor` keeps it covered.
            combine(catalog.activeAccount, locks.protectedProfiles, settings) { a, p, s -> Triple(a, p, s) }
                .retryWhen { cause, _ ->
                    if (cause is CancellationException) return@retryWhen false
                    failedRead.value = true
                    delay(READ_RETRY_MS)
                    true
                }
                .collect { (a, p, s) ->
                    step(a, p, s)
                    failedRead.value = false
                }
        }
    }

    private suspend fun step(a: AccountSummary?, p: Set<Session>, s: LockSettings) {
        val now = a?.session()
        val recheck = synchronized(lock) {
            protectedSet = p
            hide.value = s.hideInRecents || s.appLock || p.isNotEmpty()
            var ask = false
            if (!known) {
                known = true
                active = a
                relock()
            } else {
                val before = active?.session()
                active = a
                if (now != before) {
                    rechecking = null
                    decide(now, a, now != null && now in p)
                    // The observed set can lag the store by a moment: ask the store too, and judge only once it has answered.
                    ask = now != null && now !in p
                    if (ask) rechecking = now
                }
            }
            if (!s.appLock && mutableState.value == LockState.App) relock()
            publish()
            ask
        }
        if (recheck) {
            val storeSays = try {
                locks.isProtected(now!!)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                true // cannot tell: treated as protected
            }
            synchronized(lock) {
                if (rechecking == now && active?.session() == now) {
                    rechecking = null
                    if (storeSays && mutableState.value == LockState.Open) decide(now, a, true)
                    publish()
                }
            }
        }
    }

    /** Rule 3 for a change to [now]. Called holding [lock]; never suspends. */
    private fun decide(now: Session?, a: AccountSummary?, isProtected: Boolean) {
        val current = mutableState.value
        when {
            !isProtected -> if (current is LockState.Profile) set(LockState.Open)
            current == LockState.App || current == LockState.Checking -> Unit // rule 4 asks for the profile next
            proved.consume(now!!) -> set(LockState.Open)
            else -> moveTo(LockState.Profile(now, a!!.userName))
        }
    }

    /** True only while the gate has judged exactly [s] (the active session as its caller sees it) and found it open. */
    fun isOpenFor(s: Session?): Boolean = judged.value == Judged(s)

    /** The activity stopped (not a configuration change), or entered picture-in-picture. The earliest time is kept. */
    override fun onLeft() = synchronized(lock) {
        val now = clock.nowMs()
        val expected = expectedAt
        expectedAt = null
        if (expected != null && now - expected <= EXPECT_WINDOW_MS) {
            if (!ignoringTrip) excusedAt = now
            ignoringTrip = true
        } else if (!ignoringTrip) {
            away.leave(now)
        }
    }

    /** Our player is playing now, or has stopped playing (paused, ended, failed, released): `NowPlaying`, from Media3's own state. */
    fun setPlaying(playing: Boolean) = synchronized(lock) { away.setPlaying(playing, clock.nowMs()) }

    /** The activity started again, or left picture-in-picture for the full screen. */
    override fun onReturned() = synchronized(lock) {
        if (ignoringTrip) {
            ignoringTrip = false
            // An excused trip is still capped (review M1): leaving the app from inside a system screen is really leaving.
            if (known && clock.nowMs() - excusedAt >= maxOf(settings.value.lockAfter.ms, EXCUSED_CAP_MS)) relock()
            publish()
            return@synchronized
        }
        val awayMs = away.back(clock.nowMs()) ?: return@synchronized
        if (known && awayMs >= settings.value.lockAfter.ms) relock()
        publish()
    }

    /**
     * Before the app opens its own system screen (the unlock prompt, the client-certificate chooser): that trip is not "leaving".
     * It must start within [EXPECT_WINDOW_MS], so a screen that never stops the activity cannot leave the next real trip uncounted.
     */
    fun expectReturn() = synchronized(lock) { expectedAt = clock.nowMs() }

    /**
     * The system screen [expectReturn] was for has answered (yes, no or cancel). A fingerprint prompt is a system window that never
     * stops the activity, so without this its excuse would stay pending and excuse the next real trip (final review M1). A trip the
     * screen did start is already under way and is not touched.
     */
    fun tripDone() = synchronized(lock) { expectedAt = null }

    /** [DeviceAuth] said yes. */
    fun appUnlocked() = synchronized(lock) {
        if (mutableState.value == LockState.App) profileOrOpen()
        publish()
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
        scope.launch {
            val cleared = try {
                !locks.isProtected(s)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failedRead.value = true // cannot tell: still protected, and the lock screen says so (review M6)
                false
            }
            if (cleared) openIfLocked(s)
        }
    }

    private fun openIfLocked(s: Session) = synchronized(lock) {
        if ((mutableState.value as? LockState.Profile)?.session == s) set(LockState.Open)
        publish()
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
        if (a != null && a.session() in protectedSet) moveTo(LockState.Profile(a.session(), a.userName)) else set(LockState.Open)
    }

    /** Called holding [lock]. */
    private fun moveTo(locked: LockState) {
        set(locked)
        household.endPass()
    }

    /** Called holding [lock]. The state first; a lock clears [openFor] at once, and [publish] sets it again once judged. */
    private fun set(next: LockState) {
        mutableState.value = next
        if (next != LockState.Open) judged.value = null
    }

    /** Called holding [lock]: [openFor] follows the state, the active session and any pending store answer. */
    private fun publish() {
        val open = known && mutableState.value == LockState.Open && rechecking == null
        judged.value = if (open) Judged(active?.session()) else null
    }

    private fun AccountSummary.session() = Session(serverId, userId)

    override fun toString() = "LockGate(<redacted>)"

    companion object {
        /** How soon after [expectReturn] the activity must stop for that trip not to count. */
        const val EXPECT_WINDOW_MS = 10_000L

        /** How long after a failed read of the protected profiles the gate reads them again (review M6). */
        const val READ_RETRY_MS = 5_000L

        /** The longest an excused trip may last before it locks anyway (or "Lock after", if that is longer). */
        const val EXCUSED_CAP_MS = 120_000L
    }
}
