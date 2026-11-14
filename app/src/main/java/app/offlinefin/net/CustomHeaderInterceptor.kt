package app.offlinefin.net

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds user-configured headers (for example a reverse-proxy access token) to requests for the
 * server's own authority only. [allowedHosts] uses the same `host:port` form as [AuthInterceptor].
 *
 * The map is validated every time it is read: the names `Authorization`, `Host`, `Cookie` and
 * `Content-Length` are refused (any letter case), names must be valid HTTP tokens, and values must
 * not contain control characters (so CR/LF cannot split a request). Failures throw
 * [IllegalArgumentException] naming the header, never its value. The map is not read at all for
 * authorities that are not allowed.
 */
class CustomHeaderInterceptor(
    private val allowedHosts: () -> Set<String>,
    private val headers: () -> Map<String, String>,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val authority = ServerAddress.authorityOf(request.url)
        if (allowedHosts().none { it.equals(authority, ignoreCase = true) }) return chain.proceed(request)

        val builder = request.newBuilder()
        for ((name, value) in headers()) {
            validate(name, value)
            try {
                builder.header(name, value)
            } catch (_: IllegalArgumentException) {
                // OkHttp's own message can echo the value, so replace it.
                throw IllegalArgumentException("Custom header '$name' has an invalid value.")
            }
        }
        return chain.proceed(builder.build())
    }

    private fun validate(name: String, value: String) {
        require(name.isNotEmpty() && name.all { it in TOKEN_CHARS }) {
            "Custom header name contains an invalid character."
        }
        require(FORBIDDEN.none { it.equals(name, ignoreCase = true) }) { "Custom header '$name' is not allowed." }
        require(value.all { it == '\t' || it in ' '..'~' }) { "Custom header '$name' has an invalid value." }
    }

    private companion object {
        val FORBIDDEN = setOf("Authorization", "Host", "Cookie", "Content-Length")
        val TOKEN_CHARS: Set<Char> = (('a'..'z') + ('A'..'Z') + ('0'..'9') + "!#$%&'*+-.^_`|~".toList()).toSet()
    }
}
