package app.shoalwatch.net

import app.shoalwatch.core.AppError
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Refuses plain `http://` requests unless the user has opted in. Throws [BlockedRequestException]
 * (carrying `CLEARTEXT_BLOCKED`) before anything is sent. Install it as an application interceptor;
 * redirects to `http://` are caught by also installing it as a network interceptor.
 */
class CleartextGuard(private val allowCleartext: () -> Boolean) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.isHttps && !allowCleartext()) throw BlockedRequestException(AppError.of(AppError.Kind.CLEARTEXT_BLOCKED))
        return chain.proceed(request)
    }
}
