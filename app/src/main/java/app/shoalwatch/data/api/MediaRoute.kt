package app.shoalwatch.data.api

import app.shoalwatch.core.AppError
import app.shoalwatch.core.AppLog
import app.shoalwatch.data.playback.StreamSource
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.Session
import app.shoalwatch.net.BlockedRequestException
import app.shoalwatch.net.ServerAddress
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Where this session's media bytes come from: the verified address and the account's own authenticated client, guarded.
 * Obtained only from `ServerApi.mediaRoute` (see "Media route" in docs/ARCHITECTURE.md). Never printed.
 */
class MediaRoute internal constructor(
    val session: Session,
    internal val base: HttpUrl,
    internal val callFactory: Call.Factory,
    internal val connection: Connection.Online,
) {
    /** The address + [source]'s path and query, encoded by OkHttp's builder. A path that fails `ApiRequest.checkPath` is an IllegalArgumentException. */
    internal fun url(source: StreamSource): HttpUrl {
        ApiRequest.checkPath(source.path)
        return ApiRequest.url(connection.address, source.path, source.query)
    }

    override fun toString() = "MediaRoute(<redacted>)"
}

/**
 * Refuses (a failed Call carrying BlockedRequestException(AppError(FORBIDDEN))) any request outside [authority] + [videosPrefix],
 * case-insensitive on the segment, and anything not GET/HEAD.
 *
 * In full, a request is sent through [delegate] (the account's own client, so the cleartext, token, header, pin and client
 * certificate guards still apply after this one) only if ALL hold, else nothing is sent:
 * - the method is GET or HEAD;
 * - the scheme is [scheme] and the authority (`host:port`, explicit port) is [authority], with no user name or password, and the
 *   caller set no `Host` header (OkHttp derives it from the URL; a different one could steer a reverse proxy elsewhere);
 * - the decoded path segments start with those of [videosPrefix] (the address's own base path, exactly, then `videos` in any
 *   case), and at least one segment follows; no segment is empty, `.` or `..`, or holds `/`, `\` or `%` once decoded (OkHttp
 *   has already resolved dot segments, so this catches only what decoding revealed);
 * - [stale] finds nothing wrong (it names the kind of refusal otherwise): the account is still signed in (`NOT_SIGNED_IN`), the
 *   address is still the one the selector has verified for this server, and [delegate] is still the client for the server's
 *   current settings (`OFFLINE`). After a network change the state is `Checking` and the route sends nothing until the same
 *   address is verified again (a home address on another network may be someone else's machine); after a change of the
 *   server's settings or accounts it sends nothing again, because its client holds the old settings.
 * The client never follows redirects, so a 3xx comes back to the caller as a response.
 */
internal class GuardedCallFactory(
    private val delegate: Call.Factory,
    private val authority: String,
    private val videosPrefix: String,
    private val scheme: String,
    private val stale: () -> AppError.Kind?,
) : Call.Factory {
    private val prefix: List<String> = videosPrefix.split('/').filter { it.isNotEmpty() }

    init {
        require(prefix.lastOrNull().equals("videos", ignoreCase = true)) { "Invalid media prefix." }
    }

    override fun newCall(request: Request): Call = refused(request)?.let { RefusedCall(delegate.newCall(request), it) } ?: GuardedCall(delegate.newCall(request))

    /** The refusal for [request] now (logged as a fixed word), or null to send it. */
    private fun refused(request: Request): BlockedRequestException? {
        val (reason, kind) = refusalFor(request) ?: return null
        AppLog.d(TAG, "media request refused: $reason")
        return BlockedRequestException(AppError.of(kind))
    }

    /** Why [request] may not be sent (a fixed word for the log, and the kind the caller sees), or null to send it. */
    private fun refusalFor(request: Request): Pair<String, AppError.Kind>? {
        val url = request.url
        return when {
            request.method != "GET" && request.method != "HEAD" -> "method" to AppError.Kind.FORBIDDEN
            url.scheme != scheme || url.username.isNotEmpty() || url.password.isNotEmpty() || request.header("Host") != null ||
                !ServerAddress.authorityOf(url).equals(authority, ignoreCase = true) -> "target" to AppError.Kind.FORBIDDEN
            !pathAllowed(url.pathSegments) -> "path" to AppError.Kind.FORBIDDEN
            else -> stale()?.let { "stale" to it }
        }
    }

    private fun pathAllowed(segments: List<String>): Boolean {
        if (segments.size <= prefix.size) return false
        if (segments.any { it.isEmpty() || it == "." || it == ".." || it.any { c -> c == '/' || c == '\\' || c == '%' } }) return false
        val base = prefix.size - 1
        return segments.subList(0, base) == prefix.subList(0, base) && segments[base].equals("videos", ignoreCase = true)
    }

    /**
     * A call that passed when it was made, checked AGAIN when it is sent ([execute], [enqueue]): the route may have gone stale in
     * between (a sign-out, a network change, a settings change), and then it sends nothing. A [clone] is guarded the same way.
     * (A response already streaming is not cut off here: the player stops reading when playback ends.)
     */
    private inner class GuardedCall(private val inner: Call) : Call by inner {
        override fun execute(): Response {
            refused(inner.request())?.let { error -> return RefusedCall(inner, error).execute() }
            return inner.execute()
        }

        override fun enqueue(responseCallback: Callback) {
            val error = refused(inner.request()) ?: return inner.enqueue(responseCallback)
            RefusedCall(inner, error).enqueueAs(this, responseCallback)
        }

        override fun clone(): Call = newCall(inner.request())

        override fun toString() = "GuardedCall(<redacted>)"
    }

    /**
     * A call that fails without sending anything. [inner] is a real call that is never executed (it supplies the request, tags
     * and timeout); creating one opens no connection.
     */
    private class RefusedCall(private val inner: Call, private val error: BlockedRequestException) : Call by inner {
        private val executed = AtomicBoolean(false)

        override fun execute(): Response {
            check(executed.compareAndSet(false, true)) { "Already executed." }
            throw error
        }

        override fun enqueue(responseCallback: Callback) = enqueueAs(this, responseCallback)

        /** Fails [responseCallback] for [call] (the call the caller holds). */
        fun enqueueAs(call: Call, responseCallback: Callback) {
            check(executed.compareAndSet(false, true)) { "Already executed." }
            responseCallback.onFailure(call, error)
        }

        override fun isExecuted(): Boolean = executed.get()

        override fun clone(): Call = RefusedCall(inner.clone(), error)

        override fun toString() = "RefusedCall(<redacted>)"
    }

    override fun toString() = "GuardedCallFactory(<redacted>)"

    private companion object {
        const val TAG = "MediaRoute"
    }
}
