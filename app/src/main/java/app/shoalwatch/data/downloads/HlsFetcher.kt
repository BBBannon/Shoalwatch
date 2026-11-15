package app.shoalwatch.data.downloads

import app.shoalwatch.core.MonotonicClock
import app.shoalwatch.data.api.MediaRoute
import app.shoalwatch.data.playback.MediaPaths
import app.shoalwatch.data.playback.PlayMethod
import app.shoalwatch.data.playback.Resolved
import app.shoalwatch.data.playback.StreamSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.math.abs

/** One play session's conversion: held in memory only, by the queue. Never written to disk, so two sessions are never mixed. */
internal class LiveConversion internal constructor(
    val itemId: String, val resolved: Resolved, val segments: List<HlsPlaylists.Segment>, val startedAtMs: Long,
) { override fun toString() = "LiveConversion(<redacted>)" }

/**
 * A converted download: the server's HLS conversion saved segment by segment, only within the play session that produced its
 * playlist (see "Resumable fetching" in docs/ARCHITECTURE.md). [paths] builds every name; [maxSegmentBytes] is 64 MiB.
 */
internal class HlsFetcher(
    private val io: CoroutineDispatcher,
    private val clock: MonotonicClock,
    private val paths: DownloadPaths,
    private val maxSegmentBytes: Long = MAX_SEGMENT_BYTES,
) {
    /**
     * A fresh conversion for [resolved] (a TRANSCODE plan with a validated HLS source): deletes `hls/`, fetches the master and
     * media playlists through the plan's route, checks the duration sum (within 10% of [runtimeMs] when known), and writes
     * `hls/local.m3u8`. Returns the live conversion with Done, or null with a result that is not Done.
     */
    suspend fun open(resolved: Resolved, folder: File, runtimeMs: Long?): Pair<LiveConversion?, FetchResult> {
        val plan = resolved.plan
        val source = plan.source
        if (plan.method != PlayMethod.TRANSCODE || !source.hls || !MediaPaths.isServerSource(source)) return null to FetchResult.Failed(DownloadFailure.SERVER_ERROR)
        val hls = paths.hlsFolder(folder)
        if (hls.exists() && !paths.deleteInside(hls)) return null to FetchResult.Retry(app.shoalwatch.core.AppError.Kind.UNKNOWN)
        if (!hls.mkdirs() && !hls.isDirectory) return null to FetchResult.Retry(app.shoalwatch.core.AppError.Kind.UNKNOWN)
        val media = when (val first = playlist(resolved.route, source)) {
            is Fetched.Text -> when (val parsed = HlsPlaylists.parse(first.text, source.path)) {
                is HlsPlaylists.Parsed.Media -> parsed
                is HlsPlaylists.Parsed.Master -> when (val second = playlist(resolved.route, parsed.variant)) {
                    is Fetched.Text -> HlsPlaylists.parse(second.text, parsed.variant.path) as? HlsPlaylists.Parsed.Media
                    is Fetched.Stopped -> return null to second.result
                }
                is HlsPlaylists.Parsed.Refused -> null
            }
            is Fetched.Stopped -> return null to first.result
        } ?: return null to FetchResult.Failed(DownloadFailure.SERVER_ERROR)
        val sum = media.segments.sumOf { it.durationMs }
        if (runtimeMs != null && runtimeMs > 0 && abs(sum - runtimeMs) * 10 > runtimeMs) return null to FetchResult.Failed(DownloadFailure.SERVER_ERROR)
        try {
            writeSynced(paths.localPlaylist(hls), HlsPlaylists.writeLocal(media.segments.map { it.durationMs }).toByteArray())
        } catch (e: IOException) {
            return null to FetchSupport.forIo(e)
        }
        return LiveConversion(plan.itemId, resolved, media.segments, clock.nowMs()) to FetchResult.Done
    }

    /**
     * `seg-<n>.ts` from [fromSegment] for [live] only, each written to its `.part`, fsync'd and renamed before [progress] hears
     * of it. A 404 or 5xx on a segment is RestartConversion (the server dropped the session); an IOException is Retry(NETWORK)
     * (the queue keeps [live] and resumes the same session); 401 is Unauthorised. A segment is never retried here.
     */
    suspend fun fetch(live: LiveConversion, folder: File, fromSegment: Int, progress: suspend (done: Int, total: Int) -> Unit): FetchResult {
        val hls = paths.hlsFolder(folder)
        val total = live.segments.size
        for (n in fromSegment until total) {
            val result = try {
                segment(live.resolved.route, live.segments[n].source, paths.segmentPartFile(hls, n), paths.segmentFile(hls, n))
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                FetchSupport.forIo(e)
            }
            if (result != FetchResult.Done) return result
            progress(n + 1, total)
        }
        return FetchResult.Done
    }

    private sealed interface Fetched {
        class Text(val text: String) : Fetched
        class Stopped(val result: FetchResult) : Fetched
    }

    /** GET a playlist through the plan's route, at most 2 MiB. */
    private suspend fun playlist(route: MediaRoute, source: StreamSource): Fetched = try {
        FetchSupport.exchange(route, request(route, source), io) { response ->
            when {
                response.code != 200 -> Fetched.Stopped(playlistStatus(response.code))
                FetchSupport.encodedBody(response) -> Fetched.Stopped(FetchResult.Failed(DownloadFailure.SERVER_ERROR))
                else -> {
                    val body = response.body.source()
                    body.request(MAX_PLAYLIST_BYTES + 1)
                    if (body.buffer.size > MAX_PLAYLIST_BYTES) Fetched.Stopped(FetchResult.Failed(DownloadFailure.SERVER_ERROR))
                    else Fetched.Text(body.readUtf8())
                }
            }
        }
    } catch (e: IOException) {
        currentCoroutineContext().ensureActive()
        Fetched.Stopped(FetchSupport.forIo(e))
    }

    /** A playlist that is gone means the session is gone. */
    private fun playlistStatus(code: Int): FetchResult = if (code == 404) FetchResult.RestartConversion else FetchSupport.forStatus(code)

    private suspend fun segment(route: MediaRoute, source: StreamSource, part: File, target: File): FetchResult =
        FetchSupport.exchange(route, request(route, source), io) { response ->
            when {
                response.code == 404 || response.code in 500..599 -> FetchResult.RestartConversion
                response.code != 200 -> FetchSupport.forStatus(response.code)
                FetchSupport.surelyNotMedia(response.header("Content-Type")) || FetchSupport.encodedBody(response) -> FetchResult.Failed(DownloadFailure.SERVER_ERROR)
                response.body.contentLength() > maxSegmentBytes -> FetchResult.Failed(DownloadFailure.SERVER_ERROR)
                else -> save(response, part, target)
            }
        }

    /** The body into [part] (first byte 0x47, at most [maxSegmentBytes], the declared length if any), fsync'd, then renamed. */
    private fun save(response: Response, part: File, target: File): FetchResult {
        part.delete()
        val declared = response.body.contentLength()
        val source = response.body.source()
        val buffer = ByteArray(CHUNK)
        var written = 0L
        var ok = false
        try {
            FetchSupport.appendChannel(part).use { channel ->
                while (true) {
                    val n = source.read(buffer, 0, minOf(CHUNK.toLong(), maxSegmentBytes + 1 - written).toInt())
                    if (n == -1) break
                    if (written == 0L && buffer[0] != SYNC_BYTE) return FetchResult.Failed(DownloadFailure.SERVER_ERROR)
                    if (written + n > maxSegmentBytes) return FetchResult.Failed(DownloadFailure.SERVER_ERROR)
                    val bytes = ByteBuffer.wrap(buffer, 0, n)
                    while (bytes.hasRemaining()) channel.write(bytes)
                    written += n
                }
                if (written == 0L || (declared >= 0 && written != declared)) return FetchResult.Failed(DownloadFailure.SERVER_ERROR)
                channel.force(true)
            }
            FetchSupport.rename(part, target)
            ok = true
            return FetchResult.Done
        } finally {
            if (!ok) part.delete()
        }
    }

    private fun request(route: MediaRoute, source: StreamSource) =
        Request.Builder().url(route.url(source)).header("Accept-Encoding", "identity").build()

    /** Writes [bytes] to [file] and fsyncs it (the local playlist is the app's own, and small). */
    private fun writeSynced(file: File, bytes: ByteArray) = FileOutputStream(file).use { out -> out.write(bytes); out.fd.sync() }

    private companion object {
        const val MAX_SEGMENT_BYTES = 64L * 1024 * 1024
        const val MAX_PLAYLIST_BYTES = 2L * 1024 * 1024
        const val CHUNK = 64 * 1024
        const val SYNC_BYTE: Byte = 0x47
    }
}
