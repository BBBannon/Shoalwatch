package app.shoalwatch.net

import java.net.URLEncoder

/** Builds Jellyfin's `Authorization: MediaBrowser ...` header value. */
object AuthHeader {
    /**
     * Each value has `"`, `\` and control characters removed so it cannot break out of its quotes
     * or split the header, and non-ASCII characters percent-encoded (UTF-8) so OkHttp accepts them.
     */
    fun build(client: String, device: String, deviceId: String, version: String, token: String?): String =
        buildString {
            append("MediaBrowser Client=\"").append(safe(client)).append('"')
            append(", Device=\"").append(safe(device)).append('"')
            append(", DeviceId=\"").append(safe(deviceId)).append('"')
            append(", Version=\"").append(safe(version)).append('"')
            if (token != null) append(", Token=\"").append(safe(token)).append('"')
        }

    private fun safe(value: String): String = buildString {
        value.codePoints().forEach { cp ->
            when {
                cp == '"'.code || cp == '\\'.code || cp < 0x20 || cp == 0x7f -> Unit
                cp == '%'.code || cp > 0x7f -> append(URLEncoder.encode(String(Character.toChars(cp)), "UTF-8"))
                else -> append(cp.toChar())
            }
        }
    }
}
