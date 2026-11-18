package app.shoalwatch.security

import app.shoalwatch.core.MonotonicClock
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The household settings check, as screens see it (Task 6). */
interface Household {
    /** True while a protected profile exists on this phone and the active profile is not protected (or nobody is active). */
    val askNeeded: StateFlow<Boolean>

    /** The protected profiles to choose a grown-up from (names only, token-free). */
    val grownUps: Flow<List<AccountSummary>>

    /** Zeroes [pin]. Correct ⇒ household changes are allowed for 60 s, or until the active profile changes or the app locks. */
    suspend fun confirm(grownUp: Session, pin: CharArray): PinCheck
}

class HouseholdLockedException : IllegalStateException("A protected profile's PIN is needed.")

/**
 * The household check below the screens (Milestone 7): sharing, "Delete shared downloads", editing or removing a saved server, and
 * signing out another protected profile call [require] or [requireSignOut] in the data layer, so a screen that forgets to ask is
 * still refused. A pass from [confirm] lives in memory only, for [PASS_MS] of [clock] (monotonic), and ends at once when the
 * active profile changes or the app locks ([endPass]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HouseholdGuard(
    private val locks: ProfileLocks,
    private val catalog: ServerCatalog,
    private val clock: MonotonicClock,
    scope: CoroutineScope,
    /**
     * True only while the app lock has judged exactly this active session and found it open (`LockGate.isOpenFor`). A protected
     * profile that is active but locked (a Quick Connect sign-in approved elsewhere, or "Lock after" passed) is no grown-up (review I2).
     */
    private val unlocked: (Session?) -> Boolean,
) : Household {
    private class Pass(val at: Long, val active: Session?, val grownUp: Session)

    @Volatile private var pass: Pass? = null

    init {
        scope.launch {
            var first = true
            var last: Session? = null
            catalog.activeSession().distinctUntilChanged().collect {
                if (!first && it != last) endPass()
                first = false
                last = it
            }
        }
    }

    override val askNeeded: StateFlow<Boolean> =
        combine(locks.protectedProfiles, catalog.activeSession()) { set, active -> set.isNotEmpty() && (active == null || active !in set) }
            // A failed read never crashes the app (review M6): until it reads again, a grown-up is asked for.
            .retryWhen { cause, _ ->
                if (cause is CancellationException) return@retryWhen false
                emit(true)
                delay(LockGate.READ_RETRY_MS)
                true
            }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, false)

    override val grownUps: Flow<List<AccountSummary>> = locks.protectedProfiles.flatMapLatest { set ->
        val serverIds = set.map { it.serverId }.distinct()
        if (serverIds.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(serverIds.map { catalog.accounts(it) }) { lists -> lists.flatMap { l -> l.filter { Session(it.serverId, it.userId) in set } } }
        }
    }

    override suspend fun confirm(grownUp: Session, pin: CharArray): PinCheck = try {
        // The pass is for the profile that was active when the PIN was typed: a switch during the check records none (final review M6).
        val typedFor = active()
        val check = locks.check(grownUp, pin)
        if (check == PinCheck.Correct && active() == typedFor) pass = Pass(clock.nowMs(), typedFor, grownUp)
        check
    } finally {
        pin.fill('\u0000')
    }

    suspend fun allows(): Boolean {
        val protected = locks.protectedProfiles.first()
        if (protected.isEmpty()) return true
        val active = active()
        if (active != null && active in protected && unlocked(active)) return true
        return validPass(active) != null
    }

    /** Signing out [target]: allowed if [target] is not protected, or is the active profile, or [confirm] was made with [target]'s own PIN in the last 60 s. */
    suspend fun allowsSignOut(target: Session): Boolean {
        if (!locks.isProtected(target)) return true
        val active = active()
        if (target == active && unlocked(active)) return true
        return validPass(active)?.grownUp == target
    }

    /** @throws HouseholdLockedException */
    suspend fun require() {
        if (!allows()) throw HouseholdLockedException()
    }

    /** @throws HouseholdLockedException */
    suspend fun requireSignOut(target: Session) {
        if (!allowsSignOut(target)) throw HouseholdLockedException()
    }

    /**
     * Removing [serverId] signs out every profile on it, so it is the household check plus, for each protected profile on it,
     * what signing that profile out needs: its own PIN unless it is the active, open profile (final review M5). With two such
     * profiles no one pass can cover both, so one of them must be signed out first.
     * @throws HouseholdLockedException
     */
    suspend fun requireRemoveServer(serverId: String) {
        require()
        locks.protectedProfiles.first().filter { it.serverId == serverId }.forEach { requireSignOut(it) }
    }

    /** The pass ends now (LockGate calls this when the app locks). */
    fun endPass() {
        pass = null
    }

    private suspend fun active(): Session? = catalog.activeSession().first()

    private fun validPass(active: Session?): Pass? = pass?.takeIf { clock.nowMs() - it.at < PASS_MS && it.active == active }

    override fun toString() = "HouseholdGuard(<redacted>)"

    companion object {
        const val PASS_MS = 60_000L
    }
}
