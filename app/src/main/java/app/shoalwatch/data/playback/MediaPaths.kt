package app.shoalwatch.data.playback

import app.shoalwatch.data.api.ApiRequest
import app.shoalwatch.data.servers.JellyfinId

/**
 * The URL rules for media (pure). The app builds only two media paths itself (direct stream, subtitle file); every other media
 * path is one the server gave (`TranscodingUrl`) and goes through [checkServerPath] first. Paths are relative to the verified
 * address, and their segments are kept DECODED: `ApiRequest.url` encodes them again, so nothing here is ever sent half-encoded.
 * See "Media route" in docs/ARCHITECTURE.md.
 */
object MediaPaths {
    /** Media source ids, play session ids and ETags: `[A-Za-z0-9]{1,64}` (S11). */
    private val TOKEN = Regex("[A-Za-z0-9]{1,64}")

    /** What a decoded path segment may hold: unreserved characters only, and never just dots. */
    private val SEGMENT = Regex("[A-Za-z0-9._~-]+")

    private const val MAX_LENGTH = 2_048
    private const val MAX_INDEX = 10_000
    private val SUBTITLE_FORMATS = setOf("srt", "vtt", "ass", "ssa")

    /** Query names that carry a credential; compared decoded, trimmed and ignoring case (the list `AuthInterceptor` strips). */
    private val CREDENTIAL_NAMES = listOf("api_key", "ApiKey", "X-Emby-Token", "X-MediaBrowser-Token", "X-Emby-Authorization")

    fun isSafeId(value: String?): Boolean = value != null && TOKEN.matches(value)

    /**
     * GET /Videos/{itemId}/stream with static=true, mediaSourceId and tag (all three in the 12.4.0 parameter list).
     * @throws IllegalArgumentException (fixed message) unless [itemId] is a normalised `JellyfinId` and the others pass [isSafeId].
     */
    fun directStream(itemId: String, mediaSourceId: String, eTag: String?): StreamSource {
        require(isNormalisedId(itemId) && isSafeId(mediaSourceId) && (eTag == null || isSafeId(eTag))) { "Invalid media id." }
        val query = listOf("static" to "true", "mediaSourceId" to mediaSourceId) + listOfNotNull(eTag?.let { "tag" to it })
        return StreamSource("/Videos/$itemId/stream", query, hls = false)
    }

    /**
     * A path the server gave us (`TranscodingUrl`, `DeliveryUrl`): accepted only if it is relative (starts with one '/',
     * no scheme, no authority), its first segment is `videos` (any case), it passes `ApiRequest.checkPath`, and it is at most
     * 2,048 characters; credential query names (api_key, ApiKey, X-Emby-Token, X-MediaBrowser-Token, X-Emby-Authorization,
     * any case, percent-encoded or not) are removed. Null means refuse.
     *
     * Stricter than that summary: the raw text may hold no white space, control character, backslash or '#'; every path segment,
     * once percent-decoded, must be non-empty unreserved ASCII (`A-Z a-z 0-9 . _ ~ -`) and not `.` or `..` (so `%2e%2e`, `%2f`,
     * `%5c`, a double-encoded `%252e` and `;` parameters are all refused); a malformed escape refuses the whole path, as does a
     * decoded control character in the query. Query pairs keep their order; names and values are decoded once.
     */
    fun checkServerPath(raw: String?, hls: Boolean): StreamSource? {
        if (raw == null || raw.isEmpty() || raw.length > MAX_LENGTH) return null
        if (!raw.startsWith("/") || raw.startsWith("//")) return null
        if (raw.any { it == '\\' || it == '#' || it.isWhitespace() || it.isISOControl() || it.code > 0x7E }) return null
        val rawPath = raw.substringBefore('?')
        val segments = rawPath.removePrefix("/").split('/').map { decode(it, plusIsSpace = false) ?: return null }
        if (segments.size < 2 || !segments.first().equals("videos", ignoreCase = true)) return null
        if (segments.any { !SEGMENT.matches(it) || it == "." || it == ".." }) return null
        val path = "/" + segments.joinToString("/")
        try {
            ApiRequest.checkPath(path)
        } catch (_: IllegalArgumentException) {
            return null
        }
        val query = parseQuery(raw.substringAfter('?', "")) ?: return null
        return StreamSource(path, query.filterNot { (name, _) -> isCredential(name) }, hls)
    }

    /**
     * GET /Videos/{routeItemId}/{routeMediaSourceId}/Subtitles/{routeIndex}/Stream.{routeFormat}; format in srt, vtt, ass, ssa.
     * @throws IllegalArgumentException (fixed message) for an id, index or format outside those rules.
     */
    fun subtitleFile(itemId: String, mediaSourceId: String, index: Int, format: String): String {
        require(isNormalisedId(itemId) && isSafeId(mediaSourceId) && index in 0..MAX_INDEX && format in SUBTITLE_FORMATS) { "Invalid subtitle path." }
        return "/Videos/$itemId/$mediaSourceId/Subtitles/$index/Stream.$format"
    }

    private fun isNormalisedId(id: String) = JellyfinId.parseOrNull(id)?.value == id

    private fun isCredential(name: String) = CREDENTIAL_NAMES.any { it.equals(name.trim(), ignoreCase = true) }

    /** Pairs in order, decoded once ('+' is a space, as in a form); null if any escape is malformed or a control character appears. */
    private fun parseQuery(raw: String): List<Pair<String, String>>? {
        if (raw.isEmpty()) return emptyList()
        return raw.split('&').filter { it.isNotEmpty() }.map { pair ->
            val name = decode(pair.substringBefore('='), plusIsSpace = true) ?: return null
            val value = decode(pair.substringAfter('=', ""), plusIsSpace = true) ?: return null
            if ((name + value).any { it.isISOControl() } || name.isEmpty()) return null
            name to value
        }
    }

    /** Strict percent-decoding to UTF-8; null for a malformed escape or bytes that are not UTF-8. */
    private fun decode(text: String, plusIsSpace: Boolean): String? {
        if ('%' !in text && !(plusIsSpace && '+' in text)) return text
        val bytes = java.io.ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '%' -> {
                    val hi = text.getOrNull(i + 1)?.digitToIntOrNull(16) ?: return null
                    val lo = text.getOrNull(i + 2)?.digitToIntOrNull(16) ?: return null
                    bytes.write(hi * 16 + lo)
                    i += 3
                }
                c == '+' && plusIsSpace -> { bytes.write(' '.code); i++ }
                else -> { bytes.write(c.code); i++ }
            }
        }
        val decoder = Charsets.UTF_8.newDecoder()
        return try {
            decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (_: java.nio.charset.CharacterCodingException) {
            null
        }
    }
}
