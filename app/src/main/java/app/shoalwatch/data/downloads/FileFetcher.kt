package app.shoalwatch.data.downloads

import app.shoalwatch.core.AppError
import app.shoalwatch.core.MonotonicClock
import app.shoalwatch.core.SystemMonotonicClock
import app.shoalwatch.data.api.MediaRoute
import app.shoalwatch.data.playback.StreamSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Progress callback: saved bytes, only after they are on disk and fsync'd. Called at most every 2 s or 8 MiB, and at the end. */
internal fun interface Progress { suspend fun saved(doneBytes: Long, totalBytes: Long?, validator: String?, restarts: Int) }

/**
 * The original file, resumable and correct whatever the server does with `Range` (Task 1; see "Resumable fetching" in
 * docs/ARCHITECTURE.md). [channels] opens the part file for appending (a seam for tests); [clock] paces the progress saves.
 */
internal class FileFetcher(
    private val io: CoroutineDispatcher,
    private val volumes: Volumes,
    private val chunk: Int = 256 * 1024,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val channels: (File) -> FileChannel = FetchSupport::appendChannel,
) {
    /** One request's facts, carried through the handling. */
    private class Job(
        val part: File, val target: File, val from: Long, val validator: String?, val expected: Long?, val restarts: Int, val progress: Progress,
    ) { val ranged get() = from > 0 }

    /**
     * GET <route base>/Items/<itemId>/Download into [part]. It resumes from [resumeFrom] (the last fsync'd byte count in Room; the
     * file is truncated to it first; a part shorter than that starts again from zero) with `Range: bytes=<resumeFrom>-`, plus
     * `If-Range: <validator>` when one is known ([validator] must be one an earlier response for this file sent, never the media
     * source's ETag). On success the part file is renamed to [target]. [expectedBytes]: MediaSourceInfo.Size when known.
     * [restarts]: validator-change restarts so far.
     */
    suspend fun fetch(
        route: MediaRoute, itemId: String, part: File, target: File, resumeFrom: Long, validator: String?,
        expectedBytes: Long?, restarts: Int, progress: Progress,
    ): FetchResult {
        val onDisk = if (part.exists()) part.length() else 0L
        val from = if (resumeFrom in 1..onDisk) resumeFrom else 0L
        if (onDisk > from) FetchSupport.truncate(part, from) // a torn tail, or more than Room ever claimed
        val job = Job(part, target, from, validator.takeIf { from > 0 }, expectedBytes, restarts, progress)
        val request = Request.Builder()
            .url(route.url(StreamSource("/Items/$itemId/Download", emptyList(), hls = false)))
            .header("Accept-Encoding", "identity")
            .apply { if (job.ranged) header("Range", "bytes=$from-").also { job.validator?.let { v -> header("If-Range", v) } } }
            .build()
        return try {
            FetchSupport.exchange(route, request, io) { handle(job, it) }
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive() // a cancelled call is not a network failure
            FetchSupport.forIo(e)
        }
    }

    private suspend fun handle(job: Job, response: Response): FetchResult = when (response.code) {
        200 -> whole(job, response)
        206 -> rest(job, response)
        416 -> satisfied(job, response)
        else -> FetchSupport.forStatus(response.code)
    }

    /** 200: the whole file, from zero. When resuming, the server ignored Range or If-Range did not match. */
    private suspend fun whole(job: Job, response: Response): FetchResult {
        refusal(response)?.let { return it }
        val length = response.body.contentLength().takeIf { it >= 0 }
        if (length != null && job.expected != null && length != job.expected) return FetchResult.Failed(DownloadFailure.CHANGED_ON_SERVER)
        val total = length ?: job.expected ?: return FetchResult.Failed(DownloadFailure.SERVER_ERROR) // no way to know it is complete
        if (total !in 1..FetchSupport.MAX_TOTAL) return FetchResult.Failed(DownloadFailure.SERVER_ERROR)
        val validator = FetchSupport.validatorOf(response)
        var restarts = job.restarts
        if (job.ranged) {
            // Only a validator that differs from the stored one says the file changed; the same one is a server ignoring Range.
            restarts = if (job.validator != null && validator != job.validator) job.restarts + 1 else 0
            if (restarts >= MAX_RESTARTS) return FetchResult.Failed(DownloadFailure.CHANGED_ON_SERVER)
            FetchSupport.truncate(job.part, 0)
        } else {
            try {
                volumes.reserve(job.part, total) // INTERNAL only; by volume, the file's length is unchanged
            } catch (e: IOException) {
                return FetchResult.Failed(DownloadFailure.NO_SPACE)
            }
        }
        return write(job, response, 0, total, validator, restarts)
    }

    /** 206: the rest, only with an exact `Content-Range: bytes <from>-<total-1>/<total>`. */
    private suspend fun rest(job: Job, response: Response): FetchResult {
        if (!job.ranged) return restart(job) // a 206 to a request that asked for no range
        refusal(response)?.let { return it }
        val range = CONTENT_RANGE.matchEntire(response.header("Content-Range")?.trim().orEmpty())
        val start = range?.groupValues?.get(1)?.toLongOrNull()
        val end = range?.groupValues?.get(2)?.toLongOrNull()
        val total = range?.groupValues?.get(3)?.toLongOrNull()
        if (start != job.from || total == null || total !in 1..FetchSupport.MAX_TOTAL || end != total - 1) return restart(job)
        if (job.expected != null && total != job.expected) return FetchResult.Failed(DownloadFailure.CHANGED_ON_SERVER)
        val length = response.body.contentLength()
        if (length >= 0 && length != total - job.from) return restart(job)
        return write(job, response, job.from, total, FetchSupport.validatorOf(response) ?: job.validator, 0)
    }

    /** 416: the range is past the end. A part that is already the whole file is done; anything else starts again. */
    private suspend fun satisfied(job: Job, response: Response): FetchResult {
        val total = job.expected ?: UNSATISFIED.matchEntire(response.header("Content-Range")?.trim().orEmpty())?.groupValues?.get(1)?.toLongOrNull()
        if (!job.ranged || total == null || job.part.length() != total) return restart(job)
        job.progress.saved(total, total, job.validator, job.restarts)
        FetchSupport.rename(job.part, job.target)
        return FetchResult.Done
    }

    /** Cut to zero and let the next attempt start afresh (an attempt, not a validator restart). */
    private suspend fun restart(job: Job): FetchResult {
        if (job.part.exists()) FetchSupport.truncate(job.part, 0)
        job.progress.saved(0, null, null, job.restarts)
        return FetchResult.Retry(AppError.Kind.SERVER_ERROR)
    }

    /** Surely not media, or a compressed body: refused before anything is written. */
    private fun refusal(response: Response): FetchResult? =
        if (FetchSupport.surelyNotMedia(response.header("Content-Type")) || FetchSupport.encodedBody(response)) FetchResult.Failed(DownloadFailure.SERVER_ERROR)
        else null

    /**
     * Appends the body to the part file from [start] up to [total] bytes, never more (one byte past [total] is
     * `CHANGED_ON_SERVER`). Progress is saved only after `force`; a connection that fails keeps what was forced.
     */
    private suspend fun write(job: Job, response: Response, start: Long, total: Long, validator: String?, restarts: Int): FetchResult {
        val source = response.body.source()
        val buffer = ByteArray(chunk)
        var written = start
        var savedBytes = start
        var savedAt = clock.nowMs()
        channels(job.part).use { channel ->
            suspend fun save() {
                channel.force(false)
                job.progress.saved(written, total, validator, restarts)
                savedBytes = written
                savedAt = clock.nowMs()
            }
            try {
                while (true) {
                    val n = source.read(buffer, 0, minOf(chunk.toLong(), total + 1 - written).toInt())
                    if (n == -1) break
                    if (written + n > total) return FetchResult.Failed(DownloadFailure.CHANGED_ON_SERVER)
                    val bytes = ByteBuffer.wrap(buffer, 0, n)
                    while (bytes.hasRemaining()) channel.write(bytes)
                    written += n
                    if (written - savedBytes >= SAVE_BYTES || clock.nowMs() - savedAt >= SAVE_MS) save()
                }
                if (written < total) {
                    save()
                    return FetchResult.Retry(AppError.Kind.NETWORK) // the body ended early
                }
                channel.force(true)
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                if (FetchSupport.isNoSpace(e)) return FetchResult.Failed(DownloadFailure.NO_SPACE)
                try { save() } catch (_: IOException) { /* keep what Room last had */ }
                return FetchSupport.forIo(e)
            }
        }
        job.progress.saved(total, total, validator, restarts)
        FetchSupport.rename(job.part, job.target)
        return FetchResult.Done
    }

    private companion object {
        const val MAX_RESTARTS = 3
        const val SAVE_BYTES = 8L * 1024 * 1024
        const val SAVE_MS = 2_000L
        val CONTENT_RANGE = Regex("bytes (\\d{1,15})-(\\d{1,15})/(\\d{1,15})")
        val UNSATISFIED = Regex("bytes \\*/(\\d{1,15})")
    }
}
