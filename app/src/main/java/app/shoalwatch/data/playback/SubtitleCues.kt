package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.data.playback.api.PlaybackGateway
import app.shoalwatch.data.playback.engine.CueTimeline
import app.shoalwatch.data.playback.engine.SubtitleFiles
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout

/**
 * Fetches a text subtitle file and parses it with Media3's own parser for its format (`engine/SubtitleFiles`), treating it
 * as untrusted: the gateway refuses more than [MAX_BYTES] (by declared length and while reading), and this checks again
 * before parsing, so an oversized file is never copied; parsing runs on [io], never the caller's thread, and is waited for at
 * most [parseTimeoutMs] (a parse cannot be interrupted, so a slow one is abandoned, bounded by the size, and its result
 * dropped); at most [SubtitleFiles.MAX_GROUPS] cue groups. Anything unreadable is `AppError(SERVER_ERROR)`; a failure to
 * fetch is the gateway's `AppError`. Nothing from the file is logged. Holds no state of any session.
 */
class SubtitleLoader internal constructor(
    private val gateway: PlaybackGateway,
    io: CoroutineDispatcher,
    private val parse: (ByteArray, String) -> CueTimeline = SubtitleFiles::parse,
    private val parseTimeoutMs: Long = PARSE_TIMEOUT_MS,
) {
    private val parsing = CoroutineScope(SupervisorJob() + io)

    suspend fun load(s: Session, itemId: String, mediaSourceId: String, track: SubtitleTrack): CueTimeline {
        val format = track.fileFormat?.takeIf { track.kind != SubtitleKind.PICTURE } ?: throw unreadable()
        val bytes = gateway.subtitleFile(s, itemId, mediaSourceId, track.index, format)
        if (bytes.size > MAX_BYTES) throw unreadable()
        val job = parsing.async { parse(bytes, format) }
        return try {
            withTimeout(parseTimeoutMs) { job.await() }
        } catch (e: TimeoutCancellationException) {
            job.cancel()
            throw unreadable()
        } catch (e: CancellationException) {
            job.cancel()
            throw e
        } catch (e: Exception) {
            throw unreadable()
        }
    }

    private fun unreadable() = AppError.of(AppError.Kind.SERVER_ERROR)

    companion object {
        const val MAX_BYTES = 8 * 1024 * 1024
        const val PARSE_TIMEOUT_MS = 10_000L
    }
}
