package app.shoalwatch.data.playback

import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CompletableDeferred

/** What a [FakePlaybackReports] call does: answer an outcome, or throw (to prove a caller survives a seam that breaks its contract). */
sealed interface ReportOutcomeOrThrow {
    data class Outcome(val outcome: ReportOutcome) : ReportOutcomeOrThrow
    data class Throw(val error: Throwable) : ReportOutcomeOrThrow
}

/** Records every report as text (`started@0`, `progress@42000 paused`, `stopped@61000 finished`, `ping:<id>`), in order. */
class FakePlaybackReports : PlaybackReports {
    val calls = mutableListOf<String>()
    val sessions = mutableListOf<Session>()
    val stops = mutableListOf<PlaybackReport>()
    /** The session each stop was sent as, in order. */
    val stopSessions = mutableListOf<Session>()
    var answer: () -> ReportOutcomeOrThrow = { ReportOutcomeOrThrow.Outcome(ReportOutcome.Sent) }
    /** Holds every call (after it is recorded) until completed. */
    var gate: CompletableDeferred<Unit>? = null

    private suspend fun record(call: String, s: Session): ReportOutcome {
        calls += call
        sessions += s
        gate?.await()
        return when (val a = answer()) {
            is ReportOutcomeOrThrow.Outcome -> a.outcome
            is ReportOutcomeOrThrow.Throw -> throw a.error
        }
    }

    private fun PlaybackReport.text(op: String) = "$op@$positionMs${if (paused) " paused" else ""}"

    override suspend fun started(s: Session, r: PlaybackReport) = record(r.text("started"), s)
    override suspend fun progress(s: Session, r: PlaybackReport) = record(r.text("progress"), s)
    override suspend fun ping(s: Session, playSessionId: String) = record("ping:$playSessionId", s)
    override suspend fun stopped(s: Session, r: PlaybackReport, finished: Boolean): ReportOutcome {
        stops += r
        stopSessions += s
        return record("stopped@${r.positionMs}${if (finished) " finished" else ""}", s)
    }
}
