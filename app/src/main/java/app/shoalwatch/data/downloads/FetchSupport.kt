package app.shoalwatch.data.downloads

import android.system.ErrnoException
import android.system.OsConstants
import app.shoalwatch.core.AppError
import app.shoalwatch.data.api.MediaRoute
import app.shoalwatch.data.api.await
import app.shoalwatch.net.BlockedRequestException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okio.Timeout
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit

/** What one fetch step came to. Never carries anything from the server. */
internal sealed interface FetchResult {
    data object Done : FetchResult
    /** Not worth retrying by itself. */
    data class Failed(val failure: DownloadFailure) : FetchResult
    /** Network or server trouble: the worker backs off. */
    data class Retry(val kind: AppError.Kind) : FetchResult
    /** 401 on a media request. The queue decides between SIGN_IN_NEEDED and SERVER_AUTH_REFUSED (Task 10). */
    data object Unauthorised : FetchResult
    /** A converted download's play session can no longer be trusted: drop it and start that item's conversion from segment 0. */
    data object RestartConversion : FetchResult
}

/**
 * A body must bring at least [minBytes] in every [windowMs], or the read fails as a timeout (Tasks 5-8 review Minor 5: the
 * client's per-read timeout alone lets a server trickle one byte inside each window for ever).
 */
internal data class StallRule(val windowMs: Long = 60_000, val minBytes: Long = 64L * 1024)

/**
 * Enforces a [StallRule] on one body through its source's own timeout (no new client): a deadline [StallRule.windowMs] ahead,
 * moved on each time another [StallRule.minBytes] have arrived. A read past it fails with `SocketTimeoutException` (TIMEOUT).
 */
internal class StallGuard(private val timeout: Timeout, private val rule: StallRule) {
    private var since = 0L

    init { arm() }

    fun counted(n: Int) {
        since += n
        if (since >= rule.minBytes) {
            since = 0
            arm()
        }
    }

    private fun arm() { timeout.deadline(rule.windowMs, TimeUnit.MILLISECONDS) }
}

/** The shared rules of the two fetchers (media files and HLS segments): requests, statuses, errors and file writes. */
internal object FetchSupport {
    /** Content types that are surely not media; everything else (or none) is accepted, and the verifier decides by the bytes. */
    fun surelyNotMedia(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return type.startsWith("text/") || "html" in type || type == "application/json" || type == "application/xml"
    }

    /** Only `identity` (or none) is accepted: a compressed body's bytes are not the file's. */
    fun encodedBody(response: Response): Boolean = response.header("Content-Encoding")?.trim()?.lowercase()?.let { it != "identity" } == true

    /**
     * Sends [request] through [route]'s guarded call factory on [io] and hands the response to [handle], closing it afterwards.
     * Cancelling the caller cancels the call, including while the body is being read (a watchdog, as in `ServerApi`).
     * [deadlineMs]: an overall deadline for the call (review Minor 5), for small bodies only; large ones use a [StallGuard].
     */
    @OptIn(DelicateCoroutinesApi::class)
    suspend fun <T> exchange(
        route: MediaRoute, request: Request, io: CoroutineDispatcher, deadlineMs: Long? = null, handle: suspend (Response) -> T,
    ): T = withContext(io) {
        val call = route.callFactory.newCall(request)
        deadlineMs?.let { call.timeout().timeout(it, TimeUnit.MILLISECONDS) } // the whole call, body included (a playlist)
        val response = call.await()
        val watchdog = launch(Dispatchers.Unconfined, start = CoroutineStart.ATOMIC) {
            try { awaitCancellation() } finally { call.cancel() }
        }
        try {
            response.use { handle(it) }
        } finally {
            watchdog.cancel()
        }
    }

    /** A status other than success, as a result (the fetcher's own rules come first). */
    fun forStatus(code: Int): FetchResult = when (code) {
        400, 403 -> FetchResult.Failed(DownloadFailure.NOT_ALLOWED) // Jellyfin answers 400 for an item with no file to download
        401 -> FetchResult.Unauthorised
        404 -> FetchResult.Failed(DownloadFailure.GONE_FROM_SERVER)
        in 300..399 -> FetchResult.Retry(AppError.Kind.SERVER_NOT_FOUND) // no client follows redirects
        in 500..599 -> FetchResult.Retry(AppError.Kind.SERVER_ERROR)
        else -> FetchResult.Failed(DownloadFailure.SERVER_ERROR)
    }

    /** An IOException from sending or reading, as a result: a refusing route, a full disk, or the network. */
    fun forIo(e: IOException): FetchResult {
        if (isNoSpace(e)) return FetchResult.Failed(DownloadFailure.NO_SPACE)
        val kind = AppError.from(e).kind
        val blocked = causes(e).any { it is BlockedRequestException }
        return when {
            kind == AppError.Kind.NOT_SIGNED_IN -> FetchResult.Failed(DownloadFailure.SIGN_IN_NEEDED)
            blocked && kind == AppError.Kind.FORBIDDEN -> FetchResult.Failed(DownloadFailure.SERVER_ERROR) // a path the route never allows
            blocked && kind == AppError.Kind.OFFLINE -> FetchResult.Retry(AppError.Kind.OFFLINE) // a stale route: the queue asks for a new one
            kind == AppError.Kind.NETWORK || kind == AppError.Kind.UNKNOWN -> FetchResult.Retry(AppError.Kind.NETWORK)
            else -> FetchResult.Retry(kind) // TIMEOUT, TLS_PIN_CHANGED, CLEARTEXT_BLOCKED...: the queue decides
        }
    }

    fun isNoSpace(e: Throwable): Boolean = causes(e).any { it is ErrnoException && it.errno == OsConstants.ENOSPC }

    private fun causes(e: Throwable) = generateSequence(e) { it.cause }.take(10)

    /**
     * The response's validator: its ETag as sent (quotes kept), else Last-Modified, whichever is first to be 1..128 printable
     * ASCII (0x20..0x7E, so no CR or LF; an HTTP date's spaces and commas pass). Null: none, and then no If-Range is sent.
     */
    fun validatorOf(response: Response): String? =
        listOfNotNull(response.header("ETag"), response.header("Last-Modified")).firstOrNull { v ->
            v.length in 1..MAX_VALIDATOR && v.all { it.code in 0x20..0x7E }
        }

    /** Opens [file] for appending (creating it). */
    fun appendChannel(file: File): FileChannel =
        FileChannel.open(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)

    /** Cuts [file] to [length] bytes (it must be at least that long). */
    fun truncate(file: File, length: Long) = java.io.RandomAccessFile(file, "rw").use { it.setLength(length) }

    /** Renames [from] over [to] in one step. */
    fun rename(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    const val MAX_VALIDATOR = 128
    const val MAX_TOTAL = 256L * 1024 * 1024 * 1024
}
