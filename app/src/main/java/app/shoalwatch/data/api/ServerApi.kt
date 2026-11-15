package app.shoalwatch.data.api

import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.ServerClients
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.data.servers.ServerNet
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
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
 * Each call: wait for the saved sign-ins to load, ask the address selector for a verified address
 * ([ServerConnections.awaitOnline]; none means [AppError.Kind.OFFLINE] and nothing is sent), then read the server from the
 * store (after the wait, so its addresses and security settings are current), take the account's authenticated client (the
 * token is looked up live, never held here) and send. This is the only code that sends an access token on ordinary
 * requests, and it gets its address from nowhere else. Unlike sign-in it does not prefer https over a verified plain-http
 * home address. Like sign-in it cannot know the phone has not just changed network.
 *
 * Failure rules:
 * - A response is an answer and is never retried. Statuses map through [AppError.fromHttpStatus] (the one shared mapping,
 *   with the reasons for 3xx, 304 and other 4xx), except 401, which also signs the account out locally (below).
 * - 401: the server rejected this sign-in, so `ServerStore.invalidateToken` stops the app looping on a dead token (later calls
 *   fail fast as `NOT_SIGNED_IN` with nothing sent) and this call fails with "Your sign-in has expired. Sign in again." Skipped
 *   if the sign-in was replaced after the request began. Only ever for a 401 from the verified address of this signed-in call.
 * - A network failure before a response arrives asks the selector to re-probe THIS server, and only if the connection the
 *   attempt used is still the current one ([ServerConnections.refreshIfCurrent]): a burst of failures after a network
 *   change causes one probe, and a failure from before a newer verification cannot discard it. With `retry` set (default
 *   for GET and [getBytes], off for POST and DELETE) the call is then repeated exactly once through a freshly verified
 *   address (none: `OFFLINE`). A guard refusal (cleartext, signed out, certificate) is never retried. Callers must not wrap
 *   these calls in retry loops of their own: retries would multiply.
 * - A failure of the connection while reading the body maps to an error and asks for a re-check, without a retry.
 * - Bodies are bounded (on the decoded bytes) and decoded without echoing anything from them.
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

    /**
     * POST with an optional JSON body; the response body is ignored. Not retried unless the caller opts in with [retry]. With
     * [sendOnce] (admin actions) OkHttp may not resend it either (see [sentOnce]). [sendIf] is asked once more just before the
     * request is written (after the wait for a verified address): false sends nothing and fails `FORBIDDEN`.
     */
    suspend fun post(
        session: Session,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        jsonBody: String? = null,
        retry: Boolean = false,
        sendOnce: Boolean = false,
        sendIf: () -> Boolean = ALWAYS,
    ) {
        val body = jsonBody?.toRequestBody(JSON) ?: ByteArray(0).toRequestBody(null)
        execute(session, "POST", path, query, if (sendOnce) body.sentOnce() else body, retry, sendIf) { }
    }

    /**
     * POST with a JSON body, decoded as JSON (PlaybackInfo). Same rules as [get] and [post]: bounded on [maxBytes], undecodable is
     * `SERVER_ERROR`, a 401 signs the account out locally. Not retried unless the caller opts in with [retry].
     */
    suspend fun <T> postForJson(
        session: Session,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        jsonBody: String,
        deserializer: DeserializationStrategy<T>,
        maxBytes: Long = DEFAULT_MAX_BYTES,
        retry: Boolean = false,
    ): T = execute(session, "POST", path, query, jsonBody.toRequestBody(JSON), retry) { response ->
        JsonBodies.decode(JsonBodies.readBounded(response, maxBytes, AppError.Kind.SERVER_ERROR), deserializer, AppError.Kind.SERVER_ERROR)
    }

    /**
     * Where this session's media bytes come from (see "Media route" in docs/ARCHITECTURE.md). The same steps 1 to 3 as every call
     * (warm gate, `awaitOnline`, the server read after it, the address still saved, the account's authenticated client), but no
     * request is sent: the caller (the player) sends GET/HEAD requests through the route's guarded [MediaRoute.callFactory].
     * Fails `OFFLINE` (nothing verified, or the verified address is no longer saved: re-checked), `SERVER_NOT_FOUND`,
     * `NOT_SIGNED_IN` (no token for this account now), or `TLS_UNTRUSTED` (damaged pin). The route stops sending the moment the
     * selector no longer has that address verified or the server's settings or accounts change (its client is evicted), and every
     * request still reads the token live, so a sign-out stops it too. The player then asks for a new route.
     */
    internal suspend fun mediaRoute(session: Session): MediaRoute = withContext(io) {
        store.awaitWarmed()
        val online = selector.awaitOnline(session.serverId) ?: throw AppError.of(AppError.Kind.OFFLINE)
        val server = store.server(session.serverId) ?: throw AppError.of(AppError.Kind.SERVER_NOT_FOUND)
        if (online.address !in server.addresses) {
            selector.refreshIfCurrent(session.serverId, online)
            throw AppError.of(AppError.Kind.OFFLINE)
        }
        if (store.cachedToken(session.serverId, session.userId) == null) throw AppError.of(AppError.Kind.NOT_SIGNED_IN)
        val net = ServerNet.authenticated(server.addresses, server, session.userId, device, store::cachedToken)
        val base = online.address.value.toHttpUrl()
        val state = selector.connection(session.serverId)
        val client = clients.clientFor(net)
        // A settings save between the read above and clientFor would have evicted the old client, and clientFor would then have
        // cached a new one from the OLD settings, which isCurrent would keep accepting. Read again: a save after this read evicts
        // this client, so isCurrent catches it.
        val now = store.server(session.serverId)
        if (now == null || ServerNet.authenticated(now.addresses, now, session.userId, device, store::cachedToken) != net) {
            clients.evict(session.serverId, server.addresses)
            throw AppError.of(AppError.Kind.OFFLINE)
        }
        val guard = GuardedCallFactory(
            client,
            authority = online.address.authority,
            videosPrefix = (base.pathSegments.filter { it.isNotEmpty() } + "videos").joinToString("/", prefix = "/"),
            scheme = base.scheme,
            // Still signed in, still the verified address, and still the client for the server's current settings (a settings or
            // account change evicts it).
            stale = {
                when {
                    store.cachedToken(session.serverId, session.userId) == null -> AppError.Kind.NOT_SIGNED_IN
                    (state.value as? Connection.Online)?.address != online.address || !clients.isCurrent(client) -> AppError.Kind.OFFLINE
                    else -> null
                }
            },
        )
        MediaRoute(session, base, guard, online)
    }

    /** DELETE; the response body is ignored. Not retried unless the caller opts in with [retry]; [sendOnce], [sendIf] as for [post]. */
    suspend fun delete(
        session: Session,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        retry: Boolean = false,
        sendOnce: Boolean = false,
        sendIf: () -> Boolean = ALWAYS,
    ) {
        execute(session, "DELETE", path, query, if (sendOnce) ByteArray(0).toRequestBody(null).sentOnce() else null, retry, sendIf) { }
    }

    /**
     * For `sendOnce` (admin actions only): OkHttp's own `retryOnConnectionFailure` silently resends a request on a fresh connection
     * when a kept-alive one fails after the request was written, unless its body is one-shot. Marked so, a failure on a reused
     * socket surfaces as an error instead. OkHttp still recovers when nothing of the request was written (a failure while
     * connecting, or an HTTP/2 connection shut down before the stream was created). The same mark stops its follow-ups: a 503
     * with `Retry-After: 0` (or a 408) is answered to the caller, not resent, because OkHttp never follows up with a one-shot body
     * (`AdminGatewayRulesTest.anActionAnswered503WithRetryAfterZeroIsNotResent`, which fails if the mark is removed). Every other
     * caller (playback reports) keeps OkHttp's recovery, as before Milestone 6.
     */
    private fun RequestBody.sentOnce(): RequestBody {
        val inner = this
        return object : RequestBody() {
            override fun contentType() = inner.contentType()
            override fun contentLength() = inner.contentLength()
            override fun writeTo(sink: okio.BufferedSink) = inner.writeTo(sink)
            override fun isOneShot() = true
        }
    }

    /**
     * GET for artwork and other binary data. [read] gets the body as a source that fails with `SERVER_ERROR` if it
     * is read beyond [maxBytes] (a larger declared length fails before [read] runs), and the response Content-Type.
     * It runs on [io], and the response is closed when it returns or throws; the source must not be kept after that. Its result
     * is returned, and anything it throws, including an IOException of its own (a failed file write is not a network failure:
     * no re-check, no retry), propagates unchanged.
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
        val limited = LimitedSource(response.body.source(), maxBytes)
        try {
            read(limited.buffer(), response.header("Content-Type"))
        } catch (e: IOException) {
            if (limited.networkFailure != null) throw e else throw PassThrough(e)
        }
    }

    private class Open(val call: Call, val response: Response, val generation: Long)

    /** The caller's own IOException (from the [getBytes] reader) travelling out of the handler: not a network failure, rethrown as it was. */
    private class PassThrough(val original: IOException) : RuntimeException()

    @OptIn(DelicateCoroutinesApi::class) // CoroutineStart.ATOMIC, see the watchdog
    private suspend fun <T> execute(
        session: Session,
        method: String,
        path: String,
        query: List<Pair<String, String>>,
        body: RequestBody?,
        retry: Boolean,
        sendIf: () -> Boolean = ALWAYS,
        handle: (Response) -> T,
    ): T {
        ApiRequest.checkPath(path)
        return withContext(io) {
            store.awaitWarmed()
            var used: Connection.Online? = null

            /** Asks the selector to re-check this server, but only if the connection the failed attempt used is still its current one. */
            fun reportFailure(e: IOException) {
                if (!isRefusal(e)) used?.let { selector.refreshIfCurrent(session.serverId, it) }
            }

            val open = try {
                connect(session, method, path, query, body, sendIf) { used = it }
            } catch (e: IOException) {
                reportFailure(e)
                if (!retry || isRefusal(e)) throw AppError.from(e)
                used = null
                try {
                    connect(session, method, path, query, body, sendIf) { used = it }
                } catch (second: IOException) {
                    reportFailure(second)
                    throw AppError.from(second)
                }
            }
            // Reading can block, which cancelling the coroutine alone would not interrupt: cancel the call too. ATOMIC, so
            // it also runs when the coroutine was cancelled just after the response arrived.
            val watchdog = launch(Dispatchers.Unconfined, start = CoroutineStart.ATOMIC) {
                try { awaitCancellation() } finally { open.call.cancel() }
            }
            try {
                open.response.use { response ->
                    checkStatus(session, open, response.code)
                    try {
                        handle(response)
                    } catch (e: IOException) {
                        ensureActive() // a call cancelled with the caller is not a network failure
                        reportFailure(e)
                        throw AppError.from(e)
                    } catch (passThrough: PassThrough) {
                        throw passThrough.original
                    }
                }
            } finally {
                watchdog.cancel()
            }
        }
    }

    /**
     * Awaits a verified address, then reads the server (so its addresses and security settings are current), builds the
     * client and sends, if [sendIf] still says so at that moment (else `FORBIDDEN`, nothing sent). [verified] is told which
     * connection the attempt uses before anything can fail. Throws an IOException before a response arrived.
     */
    private suspend fun connect(
        session: Session,
        method: String,
        path: String,
        query: List<Pair<String, String>>,
        body: RequestBody?,
        sendIf: () -> Boolean,
        verified: (Connection.Online) -> Unit,
    ): Open {
        val online = selector.awaitOnline(session.serverId) ?: throw AppError.of(AppError.Kind.OFFLINE)
        verified(online)
        val server = store.server(session.serverId) ?: throw AppError.of(AppError.Kind.SERVER_NOT_FOUND)
        // The server was edited since that address was verified: the token goes nowhere until it is checked again.
        if (online.address !in server.addresses) throw IOException()
        val net = ServerNet.authenticated(server.addresses, server, session.userId, device, store::cachedToken)
        val request = Request.Builder().url(ApiRequest.url(online.address, path, query)).method(method, body).build()
        val generation = store.tokenGeneration(session.serverId, session.userId)
        val client = clients.clientFor(net)
        if (!sendIf()) throw AppError.of(AppError.Kind.FORBIDDEN)
        val call = client.newCall(request)
        return Open(call, call.await(), generation)
    }

    /** A guard (cleartext, signed out, client certificate, pin) refused: it carries an [AppError] and is final. */
    private fun isRefusal(e: Throwable): Boolean = generateSequence(e) { it.cause }.take(MAX_CAUSE_DEPTH).any { it is AppError }

    /**
     * Non-success statuses map through [AppError.fromHttpStatus], except 401 from the verified address: the server
     * rejected this sign-in, so it is invalidated in the store (later calls fail fast as `NOT_SIGNED_IN`, nothing is
     * sent) unless it has been replaced since this request began.
     */
    private suspend fun checkStatus(session: Session, open: Open, code: Int) {
        if (code in 200..299) return
        if (code == 401) {
            store.invalidateToken(session.serverId, session.userId, ifGeneration = open.generation)
            throw AppError(AppError.Kind.UNAUTHORISED, EXPIRED_MESSAGE)
        }
        throw AppError.fromHttpStatus(code)
    }

    /** Fails with `SERVER_ERROR` on the first byte past [max], asking the server for no more than one byte beyond it. */
    private class LimitedSource(delegate: Source, private val max: Long) : ForwardingSource(delegate) {
        private var total = 0L

        /** Set when the connection itself failed while reading, which tells that apart from an IOException of the reader's own. */
        var networkFailure: IOException? = null
            private set

        override fun read(sink: Buffer, byteCount: Long): Long {
            val n = try {
                super.read(sink, minOf(byteCount, max - total + 1))
            } catch (e: IOException) {
                networkFailure = e
                throw e
            }
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
        const val EXPIRED_MESSAGE = "Your sign-in has expired. Sign in again."
        val JSON = "application/json".toMediaType()
        val ALWAYS: () -> Boolean = { true }
    }
}
