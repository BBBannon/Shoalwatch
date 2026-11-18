package app.shoalwatch.net

import app.shoalwatch.core.AppError
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * A validated Jellyfin base URL: `http` or `https`, lower-case scheme and host, optional base path,
 * no trailing slash, no userinfo, query or fragment. An address copied from Jellyfin's web page is accepted: its fragment and
 * query are dropped, and then a final `/web`, `/web/` or `/web/index.html` (the web client), keeping any base path before it.
 *
 * [authority] is the form used everywhere to decide "is this request going to that server":
 * `host:port`, lower-case, port always explicit (443 for https, 80 for http), IPv6 hosts in brackets.
 */
class ServerAddress private constructor(url: HttpUrl, val value: String) {
    val isSecure: Boolean = url.isHttps
    val host: String = url.host
    val authority: String = authorityOf(url)

    override fun equals(other: Any?) = other is ServerAddress && other.value == value
    override fun hashCode() = value.hashCode()

    /** Never reveals the address, so it cannot reach a log through string templates. */
    override fun toString() = "<server>"

    companion object {
        private val HOST_AND_PORT = Regex("^[^/:]+:\\d+(/.*)?$")
        private val SCHEME_PREFIX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

        /** `host:port` of [url] in the form [authority] documents. */
        fun authorityOf(url: HttpUrl): String {
            val host = if (url.host.contains(':')) "[${url.host}]" else url.host
            return "$host:${url.port}"
        }

        /** @throws AppError (`SERVER_NOT_FOUND`) if [input] is not a usable http(s) server address. */
        fun parse(input: String): ServerAddress {
            val typed = input.trim()
            if (typed.isEmpty() || typed.any { it.isWhitespace() || it.isISOControl() }) throw invalid()
            // Round 4: an address copied from the browser ("…/web/#/home"). A fragment or query is never part of the server's
            // address (and never sent): everything from the first '#' or '?' goes, so the host is what was typed before it.
            val text = typed.substringBefore('#').substringBefore('?')
            if (text.isEmpty()) throw invalid()

            val withScheme = when {
                "://" in text -> text
                // "mailto:x" or "javascript:x" are schemes; "host:8096" is a host and port.
                SCHEME_PREFIX.containsMatchIn(text) && !HOST_AND_PORT.matches(text) -> throw invalid()
                else -> "https://$text"
            }
            val parsed = withScheme.toHttpUrlOrNull() ?: throw invalid()
            if (parsed.host.isEmpty() || parsed.username.isNotEmpty() || parsed.password.isNotEmpty()) throw invalid()
            // An '@' in the authority (even with an empty username) is userinfo.
            if ('@' in withScheme.substringAfter("://").substringBefore('/')) throw invalid()

            val builder = HttpUrl.Builder().scheme(parsed.scheme).host(parsed.host).port(parsed.port)
            withoutWebClient(parsed.pathSegments.filter { it.isNotEmpty() }).forEach { builder.addPathSegment(it) }
            val clean = builder.build()
            return ServerAddress(clean, clean.toString().trimEnd('/'))
        }

        /** Jellyfin's web client lives at `<base>/web/` (or `<base>/web/index.html`): the server's address is the base before it. */
        private fun withoutWebClient(segments: List<String>): List<String> = when {
            segments.size >= 2 && segments.last().equals("index.html", ignoreCase = true) && segments[segments.size - 2].equals("web", ignoreCase = true) -> segments.dropLast(2)
            segments.lastOrNull()?.equals("web", ignoreCase = true) == true -> segments.dropLast(1)
            else -> segments
        }

        private fun invalid() = AppError(
            AppError.Kind.SERVER_NOT_FOUND,
            "That doesn't look like a server address. Check it and try again.",
        )
    }
}
