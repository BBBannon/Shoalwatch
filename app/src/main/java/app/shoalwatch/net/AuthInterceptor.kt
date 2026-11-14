package app.shoalwatch.net

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.net.URLDecoder

/**
 * Keeps the login token on its own server.
 *
 * [allowedHosts] holds authorities (`host:port`, lower-case, port explicit; see
 * [ServerAddress.authority]). For every request this interceptor sees it:
 * - removes `api_key` / `ApiKey` / `X-Emby-Token` / `X-MediaBrowser-Token` / `X-Emby-Authorization` query parameters (any letter case, and percent-encoded spellings),
 *   so credentials never sit in a URL;
 * - for an allowed authority, replaces any `Authorization` header with [header] (adds nothing if null);
 * - for any other authority, removes `Authorization` and the legacy Jellyfin/Emby token headers, so a
 *   redirect or a mistaken caller cannot carry the token elsewhere. Matching is exact: no suffix or
 *   subdomain matching.
 *
 * Install it as both an application and a network interceptor, so redirects are covered too.
 */
class AuthInterceptor(
    private val allowedHosts: () -> Set<String>,
    private val header: () -> String?,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val builder = request.newBuilder().url(withoutApiKey(request.url)).removeHeader(AUTHORIZATION)
        if (isAllowed(request)) {
            header()?.let { builder.header(AUTHORIZATION, it) }
        } else {
            LEGACY_TOKEN_HEADERS.forEach { builder.removeHeader(it) }
        }
        return chain.proceed(builder.build())
    }

    private fun isAllowed(request: Request): Boolean {
        val authority = ServerAddress.authorityOf(request.url)
        return allowedHosts().any { it.equals(authority, ignoreCase = true) }
    }

    /** Drops pairs whose *decoded* name is an API key; kept pairs keep their original encoding and order. */
    private fun withoutApiKey(url: HttpUrl): HttpUrl {
        val query = url.encodedQuery ?: return url
        val pairs = query.split('&')
        val kept = pairs.filterNot { isApiKey(it.substringBefore('=')) }
        if (kept.size == pairs.size) return url
        return url.newBuilder().encodedQuery(if (kept.isEmpty()) null else kept.joinToString("&")).build()
    }

    private fun isApiKey(encodedName: String): Boolean {
        val name = try {
            URLDecoder.decode(encodedName.replace("+", "%2B"), "UTF-8")
        } catch (_: IllegalArgumentException) {
            encodedName
        }
        return API_KEYS.any { it.equals(name, ignoreCase = true) }
    }

    private companion object {
        const val AUTHORIZATION = "Authorization"
        val API_KEYS = listOf("api_key", "ApiKey", "X-Emby-Token", "X-MediaBrowser-Token", "X-Emby-Authorization")
        val LEGACY_TOKEN_HEADERS = listOf("X-Emby-Token", "X-MediaBrowser-Token", "X-Emby-Authorization")
    }
}
