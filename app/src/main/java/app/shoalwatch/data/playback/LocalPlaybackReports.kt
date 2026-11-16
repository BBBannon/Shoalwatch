package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.core.AppLog
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.downloads.LocalPlaying
import app.shoalwatch.data.library.LocalPlaystate
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.sync.OfflinePlays
import kotlinx.coroutines.CancellationException

/**
 * Reports for a LOCAL plan (a saved copy, online or not): nothing is sent from here. The position is kept in `offline_plays`
 * ([OfflinePlays]) at every report, so process death keeps it, and the stop is shown at once and queued in the durable outbox
 * ([LocalPlaystate.recordLocalStop]: a finished watch is one PLAY with its date, otherwise a POSITION), which sends it when the
 * server can be reached, so the server learns the position and the watch even for a copy played while online. [playing] knows
 * the copy is open from the start to the stop; [onFinished] runs once a finished stop is saved. Never throws except
 * CancellationException; a failure is logged by its kind and is [ReportOutcome.NotSent]. [now] is unused for now; the rows take
 * their dates from [OfflinePlays]' own clock.
 */
class LocalPlaybackReports internal constructor(
    private val plays: OfflinePlays,
    private val playstate: LocalPlaystate,
    @Suppress("unused") private val now: () -> Long,
    private val playing: LocalPlaying,
    private val onFinished: (Session, String) -> Unit,
) : PlaybackReports {
    override suspend fun started(s: Session, r: PlaybackReport): ReportOutcome {
        playing.opened(DownloadKey(s.serverId, r.itemId))
        return kept("start") { plays.position(s, r.itemId, r.positionMs) }
    }

    override suspend fun progress(s: Session, r: PlaybackReport) = kept("progress") { plays.position(s, r.itemId, r.positionMs) }

    /** A LOCAL plan has no play session, so nothing ever pings it. */
    override suspend fun ping(s: Session, playSessionId: String): ReportOutcome = ReportOutcome.NotSent(AppError.Kind.UNKNOWN)

    /** The offline play first (recovery covers a death between the two writes), then the local stop and its outbox row; then the copy is let go. */
    override suspend fun stopped(s: Session, r: PlaybackReport, finished: Boolean): ReportOutcome {
        val outcome = try {
            kept("stop") {
                plays.stopped(s, r.itemId, r.positionMs, finished)
                playstate.recordLocalStop(s, r.itemId, r.positionMs, finished)
            }
        } finally {
            playing.closed(DownloadKey(s.serverId, r.itemId))
        }
        if (finished && outcome == ReportOutcome.Sent) onFinished(s, r.itemId)
        return outcome
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
