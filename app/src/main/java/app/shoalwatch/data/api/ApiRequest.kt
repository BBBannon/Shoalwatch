package app.shoalwatch.data.api

import app.shoalwatch.net.ServerAddress
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Who a request is made as. Ids are normalised `JellyfinId` strings; nothing here is secret, but it is never printed. */
data class Session(val serverId: String, val userId: String) {
    override fun toString() = "Session(<redacted>)"
}

/** Builds and checks the URL of an API call. Every part is encoded by OkHttp's builder, never by string concatenation. */
internal object ApiRequest {
    /**
     * [path] must start with `/` and be only path: no `..` or `.` segment, `//`, `?`, `#`, backslash or control character.
     * That is a programmer error (callers pass fixed paths with ids already normalised), so a fixed message and nothing echoed.
     * @throws IllegalArgumentException
     */
    fun checkPath(path: String) {
        val ok = path.startsWith("/") && "//" !in path && ".." !in path &&
            path.none { it == '?' || it == '#' || it == '\\' ||it.isISOControl() } &&
            path.split('/').none { it == "." }
        require(ok) { "Invalid API path." }
    }

    /** [address] (which may carry a base path such as `/jellyfin`) + [path] + [query]. [path] must have passed [checkPath]. */
    fun url(address: ServerAddress, path: String, query: List<Pair<String, String>>): HttpUrl =
        address.value.toHttpUrl().newBuilder()
            .addPathSegments(path.removePrefix("/"))
            .apply { query.forEach { (name, value) -> addQueryParameter(name, value) } }
            .build()
}
