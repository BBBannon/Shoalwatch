package app.shoalwatch.data.servers

import app.shoalwatch.core.MonotonicClock
import app.shoalwatch.security.PinCheck
import app.shoalwatch.security.ProfileLocks

/** What a switch of the active profile did. Holds no PIN and no profile. */
sealed interface SwitchResult {
    data object Switched : SwitchResult

    /** Protected: ask for its PIN and call [ProfileSwitcher.switchWithPin]. Nothing changed. */
    data object NeedsPin : SwitchResult

    /** The PIN was not accepted (Wrong, Wait or Unreadable). Nothing changed. */
    data class NotSwitched(val check: PinCheck) : SwitchResult

    /** No such usable account (signed out, expired, removed). */
    data object Gone : SwitchResult
}

/** The one way a screen changes the active profile (`LockBoundaryTest`: nothing under ui/ calls `setActive`). */
interface ProfileSwitcher {
    suspend fun switchTo(serverId: String, userId: String): SwitchResult

    /** Zeroes [pin]. */
    suspend fun switchWithPin(serverId: String, userId: String, pin: CharArray): SwitchResult
}

/**
 * Switches proved by the target's own PIN, for `LockGate`: consumed once, valid for [VALID_MS] (monotonic). One slot: a newer
 * record replaces an older one, which can only make the app ask again, never less.
 */
class ProvedSwitches(private val clock: MonotonicClock) {
    private var last: Session? = null // guarded by this
    private var at = 0L

    @Synchronized fun record(s: Session) {
        last = s
        at = clock.nowMs()
    }

    /** Drops a proof for [s] that no change will use (the set failed). */
    @Synchronized fun forget(s: Session) {
        if (last == s) last = null
    }

    /** True once, within [VALID_MS] of [record] for exactly [s]. */
    @Synchronized fun consume(s: Session): Boolean {
        val fresh = last == s && clock.nowMs() - at <= VALID_MS
        if (last == s) last = null
        return fresh
    }

    override fun toString() = "ProvedSwitches(<redacted>)"

    companion object {
        const val VALID_MS = 10_000L
    }
}

/** Thrown by `StoreCatalog.setActive` for a protected profile: only [ProfileSwitch.switchWithPin] may make one active. */
class ProfileProtectedException : IllegalStateException("That profile is protected.")

/**
 * The production [ProfileSwitcher]. [setActive] is `ServerStore.setActive` itself (the catalog's own `setActive` refuses every
 * protected profile), so this is the only path that makes a protected profile active, and only after its PIN; the proof is
 * recorded first so `LockGate` sees it when the change arrives.
 */
class ProfileSwitch(
    private val setActive: suspend (serverId: String, userId: String) -> Unit,
    private val usable: suspend (Session) -> Boolean,
    /** The active session now (`ServerStore.active`). */
    private val active: suspend () -> Session?,
    private val locks: ProfileLocks,
    private val proved: ProvedSwitches,
) : ProfileSwitcher {
    override suspend fun switchTo(serverId: String, userId: String): SwitchResult {
        val s = Session(serverId, userId)
        if (!usable(s)) return SwitchResult.Gone
        if (locks.isProtected(s)) return SwitchResult.NeedsPin
        setActive(serverId, userId)
        return SwitchResult.Switched
    }

    override suspend fun switchWithPin(serverId: String, userId: String, pin: CharArray): SwitchResult = try {
        val s = Session(serverId, userId)
        when {
            !usable(s) -> SwitchResult.Gone
            !locks.isProtected(s) -> switchTo(serverId, userId)
            else -> when (val check = locks.check(s, pin)) {
                PinCheck.Correct -> {
                    // Already active: no change follows, so no proof is left lying about (final review M4).
                    if (active() != s) {
                        proved.record(s)
                        try {
                            setActive(serverId, userId)
                        } catch (e: Throwable) {
                            proved.forget(s) // nothing changed: the proof must not excuse a later change (final review M4)
                            throw e
                        }
                    }
                    SwitchResult.Switched
                }
                else -> SwitchResult.NotSwitched(check)
            }
        }
    } finally {
        pin.fill('\u0000')
    }

    override fun toString() = "ProfileSwitch(<redacted>)"
}
