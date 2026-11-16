package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.core.AppLog
import app.shoalwatch.data.library.Playstate
import app.shoalwatch.data.playback.api.PlaybackGateway
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException

/**
 * [PlaybackReports] on the server's playstate endpoints ([PlaybackGateway], so on `ServerApi`, off the main thread) and the
 * saved library ([Playstate.recordStop]). Best effort: a failure is logged by its kind only and becomes [ReportOutcome.NotSent].
 *
 * **A stop the server did not hear** because it could not be reached (`OFFLINE`, `NETWORK`, `TIMEOUT`, `SERVER_ERROR`) is
 * `recordStop(..., reached = false)`: the position is queued as POSITION with its time. A finished stop is watched on the phone at
 * once with a PLAYED tick queued, reached or not (review I1). The outbox sends it, in order, when the server is back (this class
 * never resends a stop: a session report is never replayed). Any other failure is saved on the phone only (`reached = true`, as
 * before). `NOT_SIGNED_IN` (nothing was sent) records nothing, and neither does a stop whose send overlapped a [forget] of its
 * own account or server.
 */
class ServerPlaybackReports internal constructor(
    private val gateway: PlaybackGateway,
    private val playstate: Playstate,
) : PlaybackReports {
    private val lock = Any()

    /**
     * Count [forget] calls per server and per account: a stop whose send overlapped a forget of ITS account or server is not
     * saved (its account may be the one that went). Another account going changes nothing for it.
     */
    private val serverForgets = HashMap<String, Long>()
    private val accountForgets = HashMap<Session, Long>()

    /** Under the lock. Only ever grows for [s], so a change means a forget that covers [s] happened. */
    private fun forgetsOf(s: Session): Long = (serverForgets[s.serverId] ?: 0) + (accountForgets[s] ?: 0)

    override suspend fun started(s: Session, r: PlaybackReport) = send("start") { gateway.reportStart(s, r) }

    override suspend fun progress(s: Session, r: PlaybackReport) = send("progress") { gateway.reportProgress(s, r) }

    override suspend fun ping(s: Session, playSessionId: String) = send("ping") { gateway.ping(s, playSessionId) }

    override suspend fun stopped(s: Session, r: PlaybackReport, finished: Boolean): ReportOutcome {
        val before = synchronized(lock) { forgetsOf(s) }
        val outcome = send("stop") { gateway.reportStopped(s, r) }
        val current = synchronized(lock) { forgetsOf(s) == before }
        val kind = (outcome as? ReportOutcome.NotSent)?.kind
        // A signed-out account (nothing was sent) or one that went during the send gets nothing written for it.
        if (current && kind != AppError.Kind.NOT_SIGNED_IN) record(s, r, finished, reached = kind !in UNREACHED)
        return outcome
    }

    /** A stop being sent for this account (or any account of the server) right now is not saved. Called by the store hooks. */
    fun forget(serverId: String, userId: String? = null) {
        synchronized(lock) {
            if (userId == null) serverForgets.merge(serverId, 1, Long::plus) else accountForgets.merge(Session(serverId, userId), 1, Long::plus)
        }
    }

    private suspend fun record(s: Session, r: PlaybackReport, finished: Boolean, reached: Boolean) {
        try {
            playstate.recordStop(s, r.itemId, r.positionMs, finished, reached)
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

        /** The server could not be reached: the stop is queued for the outbox to send. */
        val UNREACHED = setOf(AppError.Kind.OFFLINE, AppError.Kind.NETWORK, AppError.Kind.TIMEOUT, AppError.Kind.SERVER_ERROR)
    }
}
