package app.offlinefin.net

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
        for (ch in value) {
            when {
                ch == '"' || ch == '\\' || ch.isISOControl() -> Unit
                ch.code < 0x7f -> append(ch)
                else -> append(URLEncoder.encode(ch.toString(), "UTF-8"))
            }
        }
    }
}
