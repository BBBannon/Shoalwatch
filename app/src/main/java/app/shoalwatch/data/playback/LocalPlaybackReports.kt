package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.core.AppLog
import app.shoalwatch.data.library.LocalPlaystate
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.sync.OfflinePlays
import kotlinx.coroutines.CancellationException

/**
 * Reports for a LOCAL plan: nothing is sent; the position is kept for Milestone 5 ([OfflinePlays], `offline_plays`) and shown at
 * once ([LocalPlaystate.recordLocalStop], `user_data` with its pending marker, for this profile only). Never throws except
 * CancellationException; a failure is logged by its kind and is [ReportOutcome.NotSent]. [now] is unused for now; the rows
 * take their dates from [OfflinePlays]' own clock.
 */
class LocalPlaybackReports internal constructor(
    private val plays: OfflinePlays,
    private val playstate: LocalPlaystate,
    @Suppress("unused") private val now: () -> Long,
) : PlaybackReports {
    override suspend fun started(s: Session, r: PlaybackReport) = kept("start") { plays.position(s, r.itemId, r.positionMs) }

    override suspend fun progress(s: Session, r: PlaybackReport) = kept("progress") { plays.position(s, r.itemId, r.positionMs) }

    /** A LOCAL plan has no play session, so nothing ever pings it. */
    override suspend fun ping(s: Session, playSessionId: String): ReportOutcome = ReportOutcome.NotSent(AppError.Kind.UNKNOWN)

    /** The offline play first, so the pending marker that follows is one the sweeps spare. */
    override suspend fun stopped(s: Session, r: PlaybackReport, finished: Boolean) = kept("stop") {
        plays.stopped(s, r.itemId, r.positionMs, finished)
        playstate.recordLocalStop(s, r.itemId, r.positionMs, finished)
    }

    private suspend fun kept(what: String, block: suspend () -> Unit): ReportOutcome = try {
        block()
        ReportOutcome.Sent
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val kind = AppError.from(e).kind
        AppLog.d(TAG, "$what not kept: $kind")
        ReportOutcome.NotSent(kind)
    }

    private companion object { const val TAG = "LocalReports" }
}

/** Sends to [server] unless the report's method is LOCAL, which goes to [local]; ping always to [server] (LOCAL never pings). */
class RoutedPlaybackReports(private val server: PlaybackReports, private val local: PlaybackReports) : PlaybackReports {
    private fun of(r: PlaybackReport) = if (r.method == PlayMethod.LOCAL) local else server

    override suspend fun started(s: Session, r: PlaybackReport) = of(r).started(s, r)
    override suspend fun progress(s: Session, r: PlaybackReport) = of(r).progress(s, r)
    override suspend fun ping(s: Session, playSessionId: String) = server.ping(s, playSessionId)
    override suspend fun stopped(s: Session, r: PlaybackReport, finished: Boolean) = of(r).stopped(s, r, finished)
}
