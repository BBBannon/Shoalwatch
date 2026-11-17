package app.shoalwatch.security

import app.shoalwatch.core.AppError
import app.shoalwatch.core.MonotonicClock
import app.shoalwatch.data.db.profile.ProfileDao
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What screens use to protect their own profile (Task 7). */
interface ProfileProtection {
    val protectedProfiles: Flow<Set<Session>>
    fun isProtectedFlow(s: Session): Flow<Boolean>

    /** False (nothing stored) when [s] is already protected or [pin] breaks [PinRules]. Zeroes [pin]. */
    suspend fun protect(s: Session, pin: CharArray): Boolean

    /**
     * Zeroes both arrays. Correct ⇒ the new PIN replaces the old one.
     * @throws IllegalArgumentException when [new] breaks [PinRules] (screens check first); nothing is checked or counted then.
     */
    suspend fun changePin(s: Session, old: CharArray, new: CharArray): PinCheck

    /** Zeroes [pin]. Correct ⇒ the profile is no longer protected. */
    suspend fun unprotect(s: Session, pin: CharArray): PinCheck
}

/**
 * Per-profile PINs (Milestone 7). Room keeps only `PinHasher`'s sealed verifier and the count of wrong PINs; the PIN itself is
 * never kept, and every array passed in is filled with zeros before the call returns.
 *
 * The wait after [PinThrottle.FREE_TRIES] wrong PINs is timed only by [clock] (monotonic), from an anchor held in memory per
 * profile: the time of the last wrong PIN in this process or, if there was none, of the first check in this process. Killing the
 * app therefore restarts the wait (never shortens it), and changing the phone's clock does nothing. While waiting, a check opens
 * nothing and counts nothing. One [mutex] serialises every check, so two checks at once cannot both use the same free try.
 * Hashing and Room run on [io].
 */
class ProfileLocks(
    private val dao: ProfileDao,
    private val hasher: PinHasher,
    private val clock: MonotonicClock,
    private val io: CoroutineDispatcher,
) : ProfileProtection {
    private val mutex = Mutex()
    private val anchors = HashMap<Session, Long>() // guarded by mutex

    override val protectedProfiles: Flow<Set<Session>> =
        dao.observeProtected().map { rows -> rows.mapTo(HashSet()) { Session(it.serverId, it.userId) } }.distinctUntilChanged()

    override fun isProtectedFlow(s: Session): Flow<Boolean> = protectedProfiles.map { s in it }.distinctUntilChanged()

    suspend fun isProtected(s: Session): Boolean = withContext(io) { dao.lock(s.serverId, s.userId) != null }

    override suspend fun protect(s: Session, pin: CharArray): Boolean = try {
        PinRules.valid(pin) && mutex.withLock {
            withContext(io) {
                if (dao.lock(s.serverId, s.userId) != null) {
                    false
                } else {
                    val stored = dao.insertLock(s.serverId, s.userId, hasher.seal(pin)) != -1L
                    if (stored) anchors.remove(s)
                    stored
                }
            }
        }
    } finally {
        pin.fill('\u0000')
    }

    /**
     * Zeroes [pin]. Every try is counted in Room before the verifier is opened, and the count is put back on a right PIN; the
     * wait is measured from this process's own clock (see the class note).
     * @throws AppError when the count cannot be saved (a full or damaged database): nothing is checked then.
     */
    suspend fun check(s: Session, pin: CharArray): PinCheck = try {
        mutex.withLock { withContext(io) { checkLocked(s, pin) } }
    } finally {
        pin.fill('\u0000')
    }

    override suspend fun changePin(s: Session, old: CharArray, new: CharArray): PinCheck = try {
        require(PinRules.valid(new)) { "the new PIN breaks PinRules" }
        mutex.withLock {
            withContext(io) {
                checkLocked(s, old).also { if (it == PinCheck.Correct) dao.replaceVerifier(s.serverId, s.userId, hasher.seal(new)) }
            }
        }
    } finally {
        old.fill('\u0000')
        new.fill('\u0000')
    }

    override suspend fun unprotect(s: Session, pin: CharArray): PinCheck = try {
        mutex.withLock {
            withContext(io) {
                checkLocked(s, pin).also {
                    if (it == PinCheck.Correct) {
                        dao.deleteLock(s.serverId, s.userId)
                        anchors.remove(s)
                    }
                }
            }
        }
    } finally {
        pin.fill('\u0000')
    }

    /** After a password sign-in from "Forgot PIN" (Task 5): the profile is no longer protected. */
    internal suspend fun clear(s: Session) {
        mutex.withLock {
            withContext(io) {
                dao.deleteLock(s.serverId, s.userId)
                anchors.remove(s)
            }
        }
    }

    /** Called holding [mutex], on [io]. */
    private suspend fun checkLocked(s: Session, pin: CharArray): PinCheck {
        val lock = dao.lock(s.serverId, s.userId) ?: return PinCheck.NotProtected
        val failures = lock.failures
        if (failures >= PinThrottle.FREE_TRIES) {
            val now = clock.nowMs()
            val anchor = anchors.getOrPut(s) { now }
            val leftMs = PinThrottle.waitMs(failures) - (now - anchor)
            if (leftMs > 0) return PinCheck.Wait(((leftMs + 999) / 1000).toInt())
        }
        // Counted before the verifier is tried (review I1): if the count cannot be saved, nothing is checked, so a full disk or
        // a process killed mid-check never gives an uncounted guess.
        val counted = if (failures == Int.MAX_VALUE) failures else failures + 1
        try {
            dao.setFailures(s.serverId, s.userId, counted)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AppError.of(AppError.Kind.UNKNOWN, e)
        }
        val right = try {
            hasher.matches(pin, lock.verifier)
        } catch (e: AppError) {
            restore(s, failures) // an unreadable verifier counts nothing
            return PinCheck.Unreadable
        }
        if (right) {
            restore(s, 0)
            anchors.remove(s)
            return PinCheck.Correct
        }
        anchors[s] = clock.nowMs()
        return PinCheck.Wrong((PinThrottle.FREE_TRIES - counted).coerceAtLeast(0))
    }

    /** Best effort: if this write fails the stored count stays one higher, which can only make a wait come sooner. */
    private suspend fun restore(s: Session, failures: Int) {
        try {
            dao.setFailures(s.serverId, s.userId, failures)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
        }
    }

    override fun toString() = "ProfileLocks(<redacted>)"
}
