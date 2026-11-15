package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.core.AppLog
import app.shoalwatch.data.library.Playstate
import app.shoalwatch.data.playback.api.PlaybackGateway
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * [PlaybackReports] on the server's playstate endpoints ([PlaybackGateway], so on `ServerApi`, off the main thread) and the
 * saved library ([Playstate.recordStop]). Best effort: a failure is logged by its kind only and becomes [ReportOutcome.NotSent].
 *
 * **The one pending stop** (until Milestone 5's outbox): a stop that could not be sent because the server could not be reached
 * (`OFFLINE`, `NETWORK`, `TIMEOUT`, `SERVER_ERROR`) is kept IN MEMORY as the one pending stop for that (session, item), the
 * latest winning, and sent once more, as that same session and WITHOUT its `PlaySessionId` (so it cannot end a newer
 * transcode; AAFin's trick), the next time that server's connection becomes `Online` after not being so (a stop that failed
 * while `Online` waits for the next change and re-verification). Whatever that answer,
 * it is then dropped. [forget] drops it (account or server gone), and it is lost if the process dies (a known limit).
 * A stop that fails for any other reason (`NOT_SIGNED_IN`: nothing was sent; a refused id; a rejected sign-in) is not kept.
 */
class ServerPlaybackReports internal constructor(
    private val gateway: PlaybackGateway,
    private val playstate: Playstate,
    private val connections: ServerConnections,
    private val scope: CoroutineScope,
) : PlaybackReports {
    private class PendingStop(val report: PlaybackReport, val job: Job)

    /** Guarded by itself, with [forgets]. */
    private val pending = HashMap<Pair<Session, String>, PendingStop>()

    /** Counts [forget] calls: a stop whose send overlapped one is neither kept nor saved (its account may be the one that went). */
    private var forgets = 0L

    override suspend fun started(s: Session, r: PlaybackReport) = send("start") { gateway.reportStart(s, r) }

    override suspend fun progress(s: Session, r: PlaybackReport) = send("progress") { gateway.reportProgress(s, r) }

    override suspend fun ping(s: Session, playSessionId: String) = send("ping") { gateway.ping(s, playSessionId) }

    override suspend fun stopped(s: Session, r: PlaybackReport, finished: Boolean): ReportOutcome {
        val before = synchronized(pending) { forgets }
        val outcome = send("stop") { gateway.reportStopped(s, r) }
        val key = s to r.itemId
        val current = synchronized(pending) {
            if (forgets != before) return@synchronized false
            pending.remove(key)?.job?.cancel() // a newer stop for the item replaces (or, sent, cancels) the pending one
            if (outcome is ReportOutcome.NotSent && outcome.kind in KEPT) keep(key, r)
            true
        }
        // A signed-out account (nothing was sent) or one that went during the send gets nothing written for it.
        if (current && !(outcome is ReportOutcome.NotSent && outcome.kind == AppError.Kind.NOT_SIGNED_IN)) record(s, r, finished)
        return outcome
    }

    /** Forget everything held for this account, or for every account of the server (its pending stop). Called by the store hooks. */
    fun forget(serverId: String, userId: String? = null) {
        synchronized(pending) {
            forgets++
            val gone = pending.keys.filter { (s, _) -> s.serverId == serverId && (userId == null || s.userId == userId) }
            gone.forEach { pending.remove(it)?.job?.cancel() }
        }
    }

    /** Under the lock. The job is created before it can run, so the map holds it when it looks itself up. */
    private fun keep(key: Pair<Session, String>, r: PlaybackReport) {
        val job = scope.launch(start = CoroutineStart.LAZY) { resendWhenBack(key) }
        pending[key] = PendingStop(r.copy(playSessionId = null), job)
        job.start()
    }

    private suspend fun resendWhenBack(key: Pair<Session, String>) {
        // Online again after not being so: a stop that failed while Online waits for the next network change and re-verification.
        connections.connection(key.first.serverId).dropWhile { it is Connection.Online }.first { it is Connection.Online }
        val me = currentCoroutineContext().job
        val stop = synchronized(pending) { pending[key]?.takeIf { it.job === me }?.also { pending.remove(key) } } ?: return
        send("stop again") { gateway.reportStopped(key.first, stop.report) } // once, whatever the answer
    }

    private suspend fun record(s: Session, r: PlaybackReport, finished: Boolean) {
        try {
            playstate.recordStop(s, r.itemId, r.positionMs, finished)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.d(TAG, "stop not saved: ${AppError.from(e).kind}")
        }
    }

    private suspend fun send(what: String, block: suspend () -> Unit): ReportOutcome = try {
        block()
        ReportOutcome.Sent
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val kind = AppError.from(e).kind
        AppLog.d(TAG, "$what not sent: $kind")
        ReportOutcome.NotSent(kind)
    }

    private companion object {
        const val TAG = "PlaybackReports"
        val KEPT = setOf(AppError.Kind.OFFLINE, AppError.Kind.NETWORK, AppError.Kind.TIMEOUT, AppError.Kind.SERVER_ERROR)
    }
}
