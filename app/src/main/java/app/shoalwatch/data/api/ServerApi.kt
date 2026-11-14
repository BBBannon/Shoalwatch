package app.shoalwatch.data.api

import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.ServerClients
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.data.servers.ServerNet
import app.shoalwatch.data.servers.ServerStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Source
import okio.buffer
import java.io.IOException

/**
 * The one path every signed-in request takes (see "Authenticated requests" in docs/ARCHITECTURE.md).
 *
 * Each call: wait for the saved sign-ins to load, read the server from the store, ask the address selector for
 * a verified address ([ServerConnections.awaitOnline]; none means [AppError.Kind.OFFLINE] and nothing is sent),
 * take the account's authenticated client (the token is looked up live, never held here) and send. This is the
 * only code that sends an access token on ordinary requests, and it gets its address from nowhere else. Unlike
 * sign-in it does not prefer https over a verified plain-http home address.
 *
 * Failure rules:
 * - A response is an answer and is never retried: 401 `UNAUTHORISED`, 403 `FORBIDDEN`, 404 and any 3xx (nothing
 *   follows redirects) `SERVER_NOT_FOUND`, any other error status `SERVER_ERROR`.
 * - A network failure before a response arrives asks the selector to re-check ([ServerConnections.refresh]);
 *   with `retry` set (the default for GET, off for POST and DELETE) the call is then repeated exactly once through
 *   a freshly verified address (none: `OFFLINE`). A guard refusal (cleartext, signed out, certificate) is never retried.
 * - A failure while reading the body maps to an error and asks for a re-check, without a retry.
 * - Bodies are bounded and decoded without echoing anything from them.
 *
 * Callers pass a `path` that starts with `/` and ids already normalised by `JellyfinId`; a bad path shape is a
 * programmer error ([IllegalArgumentException]). Everything runs on [io]; calls are main-safe and cancelling the
 * caller cancels the HTTP call, including while the body is being read.
 */
class ServerApi(
    private val store: ServerStore,
    private val selector: ServerConnections,
    private val clients: ServerClients,
    private val device: DeviceIdentity,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** GET, decoded as JSON. Bodies over [maxBytes] and undecodable bodies are `SERVER_ERROR`. */
    suspend fun <T> get(
        session: Session,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        deserializer: DeserializationStrategy<T>,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        retry: Boolean = true,
    ): T = execute(session, "GET", path, query, null, retry) { response ->
        JsonBodies.decode(JsonBodies.readBounded(response, maxBytes, AppError.Kind.SERVER_ERROR), deserializer, AppError.Kind.SERVER_ERROR)
    }

    /** POST with an optional JSON body; the response body is ignored. Not retried unless the caller opts in with [retry]. */
    suspend fun post(
        session: Session,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        jsonBody: String? = null,
        retry: Boolean = false,
    ) {
        val body = jsonBody?.toRequestBody(JSON) ?: ByteArray(0).toRequestBody(null)
        execute(session, "POST", path, query, body, retry) { }
    }

    /** DELETE; the response body is ignored. Not retried unless the caller opts in with [retry]. */
    suspend fun delete(
        session: Session,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        retry: Boolean = false,
    ) {
        execute(session, "DELETE", path, query, null, retry) { }
    }

    /**
     * GET for artwork and other binary data. [read] gets the body as a source that fails with `SERVER_ERROR` if it
     * is read beyond [maxBytes] (a larger declared length fails before [read] runs), and the response Content-Type.
     * It runs on [io], and the response is closed when it returns or throws. Its result is returned.
     */
    suspend fun <T> getBytes(
        session: Session,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        maxBytes: Long,
        retry: Boolean = true,
        read: (BufferedSource, contentType: String?) -> T,
    ): T = execute(session, "GET", path, query, null, retry) { response ->
        if (response.body.contentLength() > maxBytes) throw AppError.of(AppError.Kind.SERVER_ERROR)
        read(LimitedSource(response.body.source(), maxBytes).buffer(), response.header("Content-Type"))
    }

    private class Open(val call: Call, val response: Response)

    private suspend fun <T> execute(
        session: Session,
        method: String,
        path: String,
        query: List<Pair<String, String>>,
        body: RequestBody?,
        retry: Boolean,
        handle: (Response) -> T,
    ): T {
        ApiRequest.checkPath(path)
        return withContext(io) {
            store.awaitWarmed()
            val open = try {
                connect(session, method, path, query, body)
            } catch (e: IOException) {
                if (!retry || isRefusal(e)) throw failed(e)
                selector.refresh()
                try {
                    connect(session, method, path, query, body)
                } catch (second: IOException) {
                    throw AppError.from(second)
                }
            }
            // Reading can block, which cancelling the coroutine alone would not interrupt: cancel the call too.
            val watchdog = launch(Dispatchers.Unconfined) { try { awaitCancellation() } finally { open.call.cancel() } }
            try {
                open.response.use { response ->
                    checkStatus(response.code)
                    try {
                        handle(response)
                    } catch (e: IOException) {
                        ensureActive() // a call cancelled with the caller is not a network failure
                        throw failed(e)
                    }
                }
            } finally {
                watchdog.cancel()
            }
        }
    }

    /** Verified address, authenticated client, request: the response head, or an IOException before one arrived. */
    private suspend fun connect(session: Session, method: String, path: String, query: List<Pair<String, String>>, body: RequestBody?): Open {
        val server = store.server(session.serverId) ?: throw AppError.of(AppError.Kind.SERVER_NOT_FOUND)
        val online = selector.awaitOnline(session.serverId) ?: throw AppError.of(AppError.Kind.OFFLINE)
        // The server was edited since that address was verified: the token goes nowhere until it is checked again.
        if (online.address !in server.addresses) throw IOException()
        val net = ServerNet.authenticated(server.addresses, server, session.userId, device, store::cachedToken)
        val request = Request.Builder().url(ApiRequest.url(online.address, path, query)).method(method, body).build()
        val call = clients.clientFor(net).newCall(request)
        return Open(call, call.await())
    }

    /** Asks for a re-check unless a guard refused the request (which says nothing about the network), then maps [e]. */
    private fun failed(e: IOException): AppError {
        if (!isRefusal(e)) selector.refresh()
        return AppError.from(e)
    }

    /** A guard (cleartext, signed out, client certificate, pin) refused: it carries an [AppError] and is final. */
    private fun isRefusal(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is AppError }

    private fun checkStatus(code: Int) {
        if (code in 200..299) return
        throw when (code) {
            in 300..399 -> AppError.of(AppError.Kind.SERVER_NOT_FOUND)
            401, 403, 404 -> AppError.fromHttpStatus(code)
            in 400..499 -> AppError.of(AppError.Kind.SERVER_ERROR)
            else -> AppError.fromHttpStatus(code)
        }
    }

    /** Fails with `SERVER_ERROR` on the first byte past [max], asking the server for no more than one byte beyond it. */
    private class LimitedSource(delegate: Source, private val max: Long) : ForwardingSource(delegate) {
        private var total = 0L

        override fun read(sink: Buffer, byteCount: Long): Long {
            val n = super.read(sink, minOf(byteCount, max - total + 1))
            if (n > 0) {
                total += n
                if (total > max) throw AppError.of(AppError.Kind.SERVER_ERROR)
            }
            return n
        }
    }

    private companion object {
        const val DEFAULT_MAX_BYTES = 4L * 1024 * 1024
        const val MAX_CAUSE_DEPTH = 10
        val JSON = "application/json".toMediaType()
    }
}
