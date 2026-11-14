package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.CoroutineContext

/** One list for one user on one server. Never printed. */
internal data class ListId(val serverId: String, val userId: String, val key: String) {
    constructor(s: Session, key: String) : this(s.serverId, s.userId, key)

    override fun toString() = "ListId(<redacted>)"
}

/** What the last attempt on a list did, held in memory only (after a restart it is recomputed from `list_state.refreshedAt`). */
internal sealed interface Attempt {
    data object Idle : Attempt
    data object Running : Attempt
    data class Failed(val kind: AppError.Kind) : Attempt
}

/**
 * Runs the work that refreshes a list, at most one piece of work per [ListId] at a time, and keeps what the last attempt
 * did. The work runs in the injected (app) [scope], never in the caller's, so a refresh that has begun finishes and is
 * written even if whoever asked has gone; asking only waits for it ([Job.join]) and cancelling the asker does not cancel it.
 *
 *  - a second [REFRESH] for a list joins the one running (so concurrent collectors and a pull-to-refresh cost one request);
 *  - a [MORE] is declined (null) while anything, including another [MORE], is running for the list;
 *  - a [REFRESH] asked while a [MORE] runs waits for it, then runs (a refresh replaces the list, so the two must not overlap).
 *
 * At most [maxParallel] pieces of work run at once across all lists, so a home screen with many rows is gentle on the
 * server. There are no retries here: `ServerApi` already retries a lost connection once, and retries on top would multiply.
 */
internal class Refresher(private val scope: CoroutineScope, private val context: CoroutineContext, maxParallel: Int = 4) {
    enum class Mode { REFRESH, MORE }

    private class Flight(val mode: Mode) {
        lateinit var job: Job
    }

    private val permits = Semaphore(maxParallel)
    private val lock = Any()
    private val flights = HashMap<ListId, Flight>()
    private val attempts = MutableStateFlow<Map<ListId, Attempt>>(emptyMap())

    fun attempt(id: ListId): Flow<Attempt> = attempts.map { it[id] ?: Attempt.Idle }.distinctUntilChanged()

    /**
     * Starts [work] for [id], or joins/declines as described above. The returned job completes when the work has finished
     * (it never completes exceptionally: a failure becomes [Attempt.Failed]). Null means it was declined.
     */
    fun launch(id: ListId, mode: Mode, work: suspend () -> Unit): Job? = synchronized(lock) {
        val current = flights[id]
        if (current != null && mode == Mode.MORE) return null
        if (current != null && current.mode == mode) return current.job
        val flight = Flight(mode)
        val waitFor = current?.job
        flight.job = scope.launch(context, start = CoroutineStart.LAZY) {
            val outcome: Attempt = try {
                waitFor?.join()
                permits.withPermit { work() }
                Attempt.Idle
            } catch (e: CancellationException) {
                finish(id, flight, Attempt.Idle)
                throw e
            } catch (e: Throwable) {
                Attempt.Failed(AppError.from(e).kind)
            }
            finish(id, flight, outcome)
        }
        flights[id] = flight
        setAttempt(id, Attempt.Running)
        flight.job.start()
        flight.job
    }

    private fun finish(id: ListId, flight: Flight, outcome: Attempt) = synchronized(lock) {
        // A newer flight (a refresh queued behind a load) owns the state now; this one must not overwrite it.
        if (flights[id] === flight) {
            flights.remove(id)
            setAttempt(id, outcome)
        }
    }

    private fun setAttempt(id: ListId, attempt: Attempt) = attempts.update { if (attempt == Attempt.Idle) it - id else it + (id to attempt) }
}
