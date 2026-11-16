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
 * reported (see [stopped]). A stop the server did not hear because it could not be reached (`OFFLINE`, `NETWORK`, `TIMEOUT`,
 * `SERVER_ERROR`) keeps its POSITION row; a finished stop keeps its PLAYED tick, reached or not (review I1); `NOT_SIGNED_IN` keeps
 * its row like the account's other rows (a removed account's rows go with it). The outbox sends them, in order, when it can (this
 * class never resends a stop: a session report is never replayed). Any other failure is saved on the phone only, as before.
 */
class ServerPlaybackReports internal constructor(
    private val gateway: PlaybackGateway,
    private val playstate: Playstate,
    /** Shared with the sender: the item is held from the stop's first record until its report lands or fails (Tasks 9-10 review I1). */
    private val inFlight: StopsInFlight = StopsInFlight(),
    /** Lets the outbox send for [Session] (`SyncScheduler.changed`): called after the stop report, so the report always goes first. */
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
        val outcome: ReportOutcome
        val takenBack: Boolean
        try {
            record(s, r, finished, reached = false)
            outcome = send("stop") { gateway.reportStopped(s, r) }
            val kind = (outcome as? ReportOutcome.NotSent)?.kind
            val heard = kind == null || (kind !in UNREACHED && kind != AppError.Kind.NOT_SIGNED_IN)
            takenBack = heard && !finished
            if (takenBack) record(s, r, finished = false, reached = true)
        } finally {
            inFlight.release(s, r.itemId)
        }
        if (!takenBack) changed(s) // a drain that passed over the item looks again
        return outcome
    }

    private suspend fun record(s: Session, r: PlaybackReport, finished: Boolean, reached: Boolean) {
        try {
            playstate.recordStop(s, r.itemId, r.positionMs, finished, reached, kick = false)
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
