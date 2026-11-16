package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.core.AppLog
import app.shoalwatch.data.library.Playstate
import app.shoalwatch.data.playback.api.PlaybackGateway
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.sync.StopsInFlight
import kotlinx.coroutines.CancellationException

/**
 * [PlaybackReports] on the server's playstate endpoints ([PlaybackGateway], so on `ServerApi`, off the main thread) and the
 * saved library ([Playstate.recordStop]). Best effort: a failure is logged by its kind only and becomes [ReportOutcome.NotSent].
 *
 * **Every stop is saved and queued first** (`recordStop(..., reached = false, kick = false)`, at the stop's own time), then
 * reported (see [stopped]). "Heard" is an allow-list (Tasks 9-10 review M2): the report was sent, or the server answered about
 * this item (`FORBIDDEN`, `SERVER_NOT_FOUND`); only then is an unfinished stop's POSITION row taken back. Anything else (no
 * connection, a 401, a TLS or identity failure, not signed in, unknown) keeps it, like the account's other rows (a rejected
 * sign-in keeps its changes; a removed account's go with it); a finished stop keeps its PLAYED tick either way (review I1). The
 * outbox sends them, in order, when it can (this class never resends a stop: a session report is never replayed).
 */
class ServerPlaybackReports internal constructor(
    private val gateway: PlaybackGateway,
    private val playstate: Playstate,
    /** Shared with the sender: the item is held from the stop's first record until its report lands or fails (Tasks 9-10 review I1). */
    private val inFlight: StopsInFlight = StopsInFlight(),
    /** Lets the outbox send for [Session] (`SyncScheduler.changed`): called after the stop report, so the report always goes first. */
    /** The stop's own time (review M5). Before [changed], so callers keep passing that as a trailing lambda. */
    private val now: () -> Long = System::currentTimeMillis,
    private val changed: (Session) -> Unit = {},
) : PlaybackReports {
    override suspend fun started(s: Session, r: PlaybackReport) = send("start") { gateway.reportStart(s, r) }

    override suspend fun progress(s: Session, r: PlaybackReport) = send("progress") { gateway.reportProgress(s, r) }

    override suspend fun ping(s: Session, playSessionId: String) = send("ping") { gateway.ping(s, playSessionId) }

    /**
     * Saved and queued BEFORE the report leaves (Tasks 6-8 review M3), with no send asked for yet, so a process death mid-send
     * leaves it for the outbox (the next start or the job sends it). Then the report. A heard unfinished stop (or one refused for
     * another reason) is recorded again as reached, which takes its queued position back: it is never sent twice. What is left (a
     * finished stop's PLAYED tick, or a stop the server did not hear) is handed to the outbox only now, so the report goes first.
     */
    override suspend fun stopped(s: Session, r: PlaybackReport, finished: Boolean): ReportOutcome {
        // Held for the whole report, so a drain already running for this profile cannot send the tick first (review I1).
        inFlight.hold(s, r.itemId)
        val stoppedAt = now() // both records of this stop carry it, so another stop of the item never shares its cut-off (review M5)
        val outcome: ReportOutcome
        try {
            record(s, r, finished, reached = false, stoppedAt)
            outcome = send("stop") { gateway.reportStopped(s, r) }
            val kind = (outcome as? ReportOutcome.NotSent)?.kind
            val heard = kind == null || kind in HEARD
            if (heard && !finished) record(s, r, finished = false, reached = true, stoppedAt)
        } finally {
            inFlight.release(s, r.itemId)
        }
        // After every hold (Tasks 11-12 review M1): a drain that passed over the item looks again, for what is left of the stop
        // and for anything the user changed while the report was in flight. Cheap when nothing is due.
        changed(s)
        return outcome
    }

    private suspend fun record(s: Session, r: PlaybackReport, finished: Boolean, reached: Boolean, stoppedAt: Long) {
        try {
            playstate.recordStop(s, r.itemId, r.positionMs, finished, reached, kick = false, stoppedAt = stoppedAt)
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

        /** The server answered about this item: the report was heard, so its position is not sent again. */
        val HEARD = setOf(AppError.Kind.FORBIDDEN, AppError.Kind.SERVER_NOT_FOUND)
    }
}
