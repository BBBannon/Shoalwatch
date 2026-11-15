package app.shoalwatch.data.downloads

import app.shoalwatch.core.AppError
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

/** Where a conversion is written, and the size the plan expected for it (null: not known). */
internal data class ConversionSpace(val storage: StorageKind, val volumeId: String?, val plannedBytes: Long?)

/**
 * One play session's conversion: held in memory only, by the queue. Never written to disk, so two sessions are never mixed.
 * [budgetBytes]: the most all its segments together may take; [bytesPerSecond]: its quality's bitrate / 8.
 */
internal class LiveConversion internal constructor(
    val itemId: String, val resolved: Resolved, val segments: List<HlsPlaylists.Segment>, val startedAtMs: Long,
    internal val space: ConversionSpace, internal val budgetBytes: Long, internal val bytesPerSecond: Long,
) { override fun toString() = "LiveConversion(<redacted>)" }

/**
 * A converted download: the server's HLS conversion saved segment by segment, only within the play session that produced its
 * playlist (see "Resumable fetching" in docs/ARCHITECTURE.md). [paths] builds every name. Bounds (Tasks 5-8 review I-3): a
 * total budget, a per-segment cap from the bitrate (never above [maxSegmentBytes], 64 MiB), free space above the plan's
 * reserve before every segment, and the budget reserved on internal storage. [stall] cuts a trickling segment; each playlist
 * call has [playlistMs] in all (review Minor 5).
 */
internal class HlsFetcher(
    private val io: CoroutineDispatcher,
    private val clock: MonotonicClock,
    private val paths: DownloadPaths,
    private val volumes: Volumes,
    private val maxSegmentBytes: Long = MAX_SEGMENT_BYTES,
    private val stall: StallRule = StallRule(),
    private val playlistMs: Long = PLAYLIST_MS,
) : ConversionFetch {
    /**
     * A fresh conversion for [resolved] (a TRANSCODE plan with a validated HLS source): deletes `hls/`, fetches the master and
     * media playlists through the plan's route, checks the duration sum (within 10% of [runtimeMs] when known), and writes
     * `hls/local.m3u8`. Its budget is 2 x the bitrate x the longer of the playlist's sum and the runtime, plus 8 MiB, and never
     * above the planned size x 3 + 8 MiB; on INTERNAL it is reserved (a refusal is NO_SPACE). Returns the live conversion with
     * Done, or null with a result that is not Done.
     */
    override suspend fun open(resolved: Resolved, folder: File, runtimeMs: Long?, space: ConversionSpace): Pair<LiveConversion?, FetchResult> {
        val plan = resolved.plan
        val source = plan.source
        if (plan.method != PlayMethod.TRANSCODE || !source.hls || !MediaPaths.isServerSource(source)) return null to FetchResult.Failed(DownloadFailure.SERVER_ERROR)
        val hls = paths.hlsFolder(folder)
        if (hls.exists() && !paths.deleteInside(hls)) return null to FetchResult.Retry(AppError.Kind.UNKNOWN)
        if (!hls.mkdirs() && !hls.isDirectory) return null to FetchResult.Retry(AppError.Kind.UNKNOWN)
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
        val perSecond = plan.request.quality.maxBitrate.toLong() / 8
        val budget = budget(perSecond, maxOf(sum, runtimeMs ?: 0), space.plannedBytes)
        try {
            val playlist = paths.localPlaylist(hls)
            if (space.storage == StorageKind.INTERNAL) {
                try {
                    volumes.reserve(playlist, budget) // by volume; nothing's length changes
                } catch (e: IOException) {
                    return null to FetchResult.Failed(DownloadFailure.NO_SPACE)
                }
            }
            writeSynced(playlist, HlsPlaylists.writeLocal(media.segments.map { it.durationMs }).toByteArray())
        } catch (e: IOException) {
            return null to FetchSupport.forIo(e)
        }
        return LiveConversion(plan.itemId, resolved, media.segments, clock.nowMs(), space, budget, perSecond) to FetchResult.Done
    }

    /**
     * `seg-<n>.ts` from [fromSegment] for [live] only, each written to its `.part`, fsync'd and renamed before [progress] hears
     * of it. Before each one, the volume must have its cap free above the reserve (else NO_SPACE); the segments saved so far
     * count towards the budget, and going past it, or past a segment's cap, is SERVER_ERROR. A 404 or 5xx on a segment is
     * RestartConversion (the server dropped the session); an IOException is a retry (the queue keeps [live] and resumes the same
     * session); 401 is Unauthorised. A segment is never retried here.
     */
    override suspend fun fetch(live: LiveConversion, folder: File, fromSegment: Int, progress: suspend (done: Int, total: Int) -> Unit): FetchResult {
        val hls = paths.hlsFolder(folder)
        val total = live.segments.size
        var used = (0 until fromSegment.coerceAtMost(total)).sumOf { paths.segmentFile(hls, it).length() }
        val reserve = if (live.space.storage == StorageKind.INTERNAL) INTERNAL_RESERVE else REMOVABLE_RESERVE
        for (n in fromSegment until total) {
            val cap = segmentCap(live.bytesPerSecond, live.segments[n].durationMs)
            if (volumes.freeBytes(live.space.storage, live.space.volumeId) - cap < reserve) return FetchResult.Failed(DownloadFailure.NO_SPACE)
            val limit = minOf(cap, live.budgetBytes - used)
            if (limit <= 0) return FetchResult.Failed(DownloadFailure.SERVER_ERROR)
            val target = paths.segmentFile(hls, n)
            val result = try {
                segment(live.resolved.route, live.segments[n].source, paths.segmentPartFile(hls, n), target, limit)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                FetchSupport.forIo(e)
            }
            if (result != FetchResult.Done) return result
            used += target.length()
            progress(n + 1, total)
        }
        return FetchResult.Done
    }

    /** 2 x the bitrate x the duration, plus an allowance; never above the planned size x 3 plus the allowance. Saturating. */
    private fun budget(perSecond: Long, durationMs: Long, planned: Long?): Long {
        val seconds = (durationMs + 999) / 1_000
        val byRate = if (seconds > 0 && perSecond > Long.MAX_VALUE / 2 / seconds) Long.MAX_VALUE - ALLOWANCE else 2 * perSecond * seconds
        val byPlan = planned?.takeIf { it > 0 && it < (Long.MAX_VALUE - ALLOWANCE) / PLANNED_FACTOR }?.let { it * PLANNED_FACTOR } ?: Long.MAX_VALUE - ALLOWANCE
        return minOf(byRate, byPlan) + ALLOWANCE
    }

    /** max(4 MiB, 2 x the bitrate x this segment's duration), and never above [maxSegmentBytes]. */
    private fun segmentCap(perSecond: Long, durationMs: Long): Long =
        maxOf(MIN_SEGMENT_CAP, 2 * perSecond * ((durationMs + 999) / 1_000)).coerceAtMost(maxSegmentBytes)

    private sealed interface Fetched {
        class Text(val text: String) : Fetched
        class Stopped(val result: FetchResult) : Fetched
    }

    /** GET a playlist through the plan's route, at most 2 MiB and [playlistMs] in all. */
    private suspend fun playlist(route: MediaRoute, source: StreamSource): Fetched = try {
        FetchSupport.exchange(route, request(route, source), io, deadlineMs = playlistMs) { response ->
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

    private suspend fun segment(route: MediaRoute, source: StreamSource, part: File, target: File, limit: Long): FetchResult =
        FetchSupport.exchange(route, request(route, source), io) { response ->
            when {
                response.code == 404 || response.code in 500..599 -> FetchResult.RestartConversion
                response.code != 200 -> FetchSupport.forStatus(response.code)
                FetchSupport.surelyNotMedia(response.header("Content-Type")) || FetchSupport.encodedBody(response) -> FetchResult.Failed(DownloadFailure.SERVER_ERROR)
                response.body.contentLength() > limit -> FetchResult.Failed(DownloadFailure.SERVER_ERROR)
                else -> save(response, part, target, limit)
            }
        }

    /** The body into [part] (first byte 0x47, at most [limit] bytes, the declared length if any), fsync'd, then renamed. */
    private fun save(response: Response, part: File, target: File, limit: Long): FetchResult {
        part.delete()
        val declared = response.body.contentLength()
        val source = response.body.source()
        val guard = StallGuard(source.timeout(), stall)
        val buffer = ByteArray(CHUNK)
        var written = 0L
        var ok = false
        try {
            FetchSupport.appendChannel(part).use { channel ->
                while (true) {
                    val n = source.read(buffer, 0, minOf(CHUNK.toLong(), limit + 1 - written).toInt())
                    if (n == -1) break
                    guard.counted(n)
                    if (written == 0L && buffer[0] != SYNC_BYTE) return FetchResult.Failed(DownloadFailure.SERVER_ERROR)
                    if (written + n > limit) return FetchResult.Failed(DownloadFailure.SERVER_ERROR)
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
        const val MIN_SEGMENT_CAP = 4L * 1024 * 1024
        const val ALLOWANCE = 8L * 1024 * 1024
        const val PLANNED_FACTOR = 3
        /** The plan's reserves (Decisions: 1 GiB internal, 256 MiB on a card), as `DownloadPlanner` keeps them. */
        const val INTERNAL_RESERVE = 1L shl 30
        const val REMOVABLE_RESERVE = 256L shl 20
        const val PLAYLIST_MS = 60_000L
        const val MAX_PLAYLIST_BYTES = 2L * 1024 * 1024
        const val CHUNK = 64 * 1024
        const val SYNC_BYTE: Byte = 0x47
    }
}
