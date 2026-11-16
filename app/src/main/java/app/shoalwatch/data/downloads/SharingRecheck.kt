package app.shoalwatch.data.downloads

import app.shoalwatch.core.AppLog
import app.shoalwatch.core.MonotonicClock
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * How old a sharing confirmation may be: 7 days by the wall clock. One dated more than [SKEW_MS] ahead of the clock is stale
 * too, so setting the device's date back cannot keep a confirmation young (final review L-2).
 */
internal object ConfirmationAge {
    const val STALE_AFTER_MS = 7L * 24 * 60 * 60 * 1_000
    const val SKEW_MS = 5L * 60 * 1_000

    fun fresh(confirmedAt: Long, now: Long): Boolean = confirmedAt <= now + SKEW_MS && now - confirmedAt <= STALE_AFTER_MS
}

/**
 * WHEN the strict sharing rule's re-ask (`DownloadHousekeeping.reconfirm`) runs for the active profile, whichever screen is open
 * (Tasks 13-16 review I-1): on a session change, when the connection to its server comes back, when the app comes to the front,
 * and when playback finds a stale shared copy (`SavedCopies.plan`).
 *
 * - Only while [reachable] (offline, a confirmation that cannot be re-asked stays valid however old).
 * - **Rate limited:** at most one run per profile every [MIN_GAP_MS] (monotonic), and one run at a time, so screens, a flapping
 *   connection or repeated plays cannot hammer the server. A run with nothing stale costs one Room query and no request.
 */
internal class SharingRecheck(
    /** The re-ask for a session; the item about to play, when a play asked for it, is asked about first (final review L-11). */
    private val reconfirm: suspend (Session, String?) -> Unit,
    private val reachable: (Session) -> Boolean,
    private val scope: CoroutineScope,
    private val clock: MonotonicClock,
) {
    private val running = Mutex()
    private val lastRun = HashMap<Session, Long>()

    /**
     * Runs the re-ask for [s] unless it cannot be reached or ran within [MIN_GAP_MS]; the run's job, or null when skipped.
     * [first]: the item about to play, asked about before any other.
     */
    fun request(s: Session, first: String? = null): Job? {
        if (!reachable(s)) return null
        val now = clock.nowMs()
        synchronized(lastRun) {
            val last = lastRun[s]
            if (last != null && now - last < MIN_GAP_MS) return null
            lastRun[s] = now
        }
        return scope.launch {
            try {
                running.withLock { reconfirm(s, first) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) { // reconfirm logs and swallows its own failures; this is a last guard
                AppLog.w(TAG, "sharing recheck failed", e)
            }
        }
    }

    /** From app start: the active session on each change, and again each time its server becomes reachable after not being. */
    fun watch(active: Flow<Session?>, regained: Flow<Session>): Job = scope.launch {
        launch { active.filterNotNull().distinctUntilChanged().collect { request(it) } }
        launch { regained.collect { request(it) } }
    }

    /** The app came to the front: the active profile's re-ask (rate limited like every other trigger). */
    suspend fun inFront(active: Flow<Session?>): Job? = active.first()?.let(::request)

    private companion object {
        const val TAG = "SharingRecheck"
        const val MIN_GAP_MS = 5L * 60 * 1_000
    }
}
