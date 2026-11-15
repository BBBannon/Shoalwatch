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

    /** The last attempt succeeded. Kept (not forgotten) so that a screen never sees "nothing running, nothing saved" in the instant between the write committing and Room reporting it. */
    data object Done : Attempt
    /** [at] is when it failed (epoch ms, the repository's clock). */
    data class Failed(val kind: AppError.Kind, val at: Long) : Attempt
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
 * What the last attempt did is remembered per list until a later attempt succeeds, the account is [forget]-ed (removed or
 * signed out), or more than [MAX_ENTRIES] lists are on record (the oldest finished ones are dropped).
 *
 * At most [maxParallel] pieces of work run at once across all lists, so a home screen with many rows is gentle on the
 * server. There are no retries here: `ServerApi` already retries a lost connection once, and retries on top would multiply.
 */
internal class Refresher(private val scope: CoroutineScope, private val context: CoroutineContext, private val clock: () -> Long, maxParallel: Int = 4) {
    enum class Mode { REFRESH, MORE }

    private class Flight(val mode: Mode) {
        lateinit var job: Job
    }

    private val permits = Semaphore(maxParallel)
    private val lock = Any()
    private val flights = HashMap<ListId, Flight>()
    private val attempts = MutableStateFlow<Map<ListId, Attempt>>(emptyMap())

    /** Every list whose last attempt failed. */
    fun failures(): Map<ListId, Attempt.Failed> = attempts.value.filterValues { it is Attempt.Failed }.mapValues { it.value as Attempt.Failed }

    /** The failure on record for [id], if any (used for the retry cooldown). */
    fun lastFailure(id: ListId): Attempt.Failed? = attempts.value[id] as? Attempt.Failed

    /** Drops the failures on record for one account, or for every account of the server when [userId] is null. Work in flight is left alone. */
    fun forget(serverId: String, userId: String?) = attempts.update { cur ->
        cur.filterNot { (id, a) -> a != Attempt.Running && id.serverId == serverId && (userId == null || id.userId == userId) }
    }

    /** The work running for [id] right now, if any (a caller can wait for it to finish; it never completes exceptionally). */
    fun inFlight(id: ListId): Job? = synchronized(lock) { flights[id]?.job }

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
                Attempt.Done
            } catch (e: CancellationException) {
                finish(id, flight, Attempt.Idle)
                throw e
            } catch (e: Throwable) {
                Attempt.Failed(AppError.from(e).kind, clock())
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

    private fun setAttempt(id: ListId, attempt: Attempt) = attempts.update { cur ->
        if (attempt == Attempt.Idle) return@update cur - id
        val next = LinkedHashMap(cur)
        next.remove(id) // so the entry is the newest
        next[id] = attempt
        val oldest = next.entries.iterator()
        while (next.size > MAX_ENTRIES && oldest.hasNext()) if (oldest.next().value != Attempt.Running) oldest.remove()
        next
    }

    private companion object {
        const val MAX_ENTRIES = 500
    }
}
