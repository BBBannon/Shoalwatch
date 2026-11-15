package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.Session

/**
 * One report of where playback is, for the server's playstate endpoints. Ids are the validated ones from the plan
 * (`itemId` a normalised `JellyfinId`, `mediaSourceId` and `playSessionId` `[A-Za-z0-9]{1,64}`); the gateway checks
 * them again and sends nothing if they fail. Never printed: it holds an item and a position.
 */
data class PlaybackReport(
    val itemId: String, val mediaSourceId: String, val playSessionId: String?, val method: PlayMethod,
    val positionMs: Long, val paused: Boolean, val audioIndex: Int?, val subtitleIndex: Int?, val failed: Boolean = false,
) { override fun toString() = "PlaybackReport(<redacted>)" }

sealed interface ReportOutcome { data object Sent : ReportOutcome; data class NotSent(val kind: AppError.Kind) : ReportOutcome }

/**
 * What the player tells the server (and the saved library) as it plays. NEVER throws except CancellationException: a failed
 * report must not interrupt the film. Milestone 5 replaces the NotSent path of [stopped] with the durable outbox; the player
 * does not change. Every report is sent as the [Session] it is given, and only as that session. See "Progress reports" in
 * docs/ARCHITECTURE.md.
 */
interface PlaybackReports {
    suspend fun started(s: Session, r: PlaybackReport): ReportOutcome
    suspend fun progress(s: Session, r: PlaybackReport): ReportOutcome
    suspend fun ping(s: Session, playSessionId: String): ReportOutcome
    /** [finished]: reached the credits (Outro start) or 90% of the runtime, by playback (SPEC 3.1). */
    suspend fun stopped(s: Session, r: PlaybackReport, finished: Boolean): ReportOutcome
}
