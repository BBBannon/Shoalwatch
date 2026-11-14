package app.offlinefin.net

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/**
 * Keeps the login token on its own server.
 *
 * [allowedHosts] holds authorities (`host:port`, lower-case, port explicit; see
 * [ServerAddress.authority]). For every request this interceptor sees it:
 * - removes `api_key` / `ApiKey` query parameters (any letter case), so credentials never sit in a URL;
 * - for an allowed authority, replaces any `Authorization` header with [header] (adds nothing if null);
 * - for any other authority, removes `Authorization`, so a redirect or a mistaken caller cannot carry
 *   the token elsewhere. Matching is exact: no suffix or subdomain matching.
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
        if (isAllowed(request)) header()?.let { builder.header(AUTHORIZATION, it) }
        return chain.proceed(builder.build())
    }

    private fun isAllowed(request: Request): Boolean {
        val authority = ServerAddress.authorityOf(request.url)
        return allowedHosts().any { it.equals(authority, ignoreCase = true) }
    }

    private fun withoutApiKey(url: HttpUrl): HttpUrl {
        val keys = url.queryParameterNames.filter { name -> API_KEYS.any { it.equals(name, ignoreCase = true) } }
        if (keys.isEmpty()) return url
        return url.newBuilder().apply { keys.forEach { removeAllQueryParameters(it) } }.build()
    }

    private companion object {
        const val AUTHORIZATION = "Authorization"
        val API_KEYS = listOf("api_key", "ApiKey")
    }
}
