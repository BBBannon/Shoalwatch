package app.offlinefin.data.servers

import android.content.Context
import android.os.Build
import app.offlinefin.BuildConfig
import app.offlinefin.net.AuthHeader
import java.util.UUID

/**
 * How this install identifies itself to Jellyfin: a random id generated once and kept in
 * app-private preferences (excluded from backup, so a restored copy gets its own id), plus names.
 */
class DeviceIdentity(
    val deviceId: String,
    val deviceName: String,
    val clientName: String,
    val clientVersion: String,
) {
    /** The `Authorization: MediaBrowser ...` value; includes the token when one is given. */
    fun authHeader(token: String?): String = AuthHeader.build(clientName, deviceName, deviceId, clientVersion, token)

    override fun toString() = "DeviceIdentity"

    companion object {
        private const val PREFS = "device_identity"
        private const val KEY_ID = "device_id"
        private const val MAX_NAME = 64

        fun load(context: Context): DeviceIdentity {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val id = prefs.getString(KEY_ID, null)?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString().also { prefs.edit().putString(KEY_ID, it).apply() }
            return DeviceIdentity(id, sanitiseDeviceName(Build.MODEL), "OfflineFin", BuildConfig.VERSION_NAME)
        }

        /** Control characters become spaces, quotes and backslashes go, whitespace collapses; at most 64 characters. */
        fun sanitiseDeviceName(raw: String?): String {
            val cleaned = (raw ?: "")
                .map { if (it.isISOControl()) ' ' else it }
                .filterNot { it == '"' || it == '\\' }
                .joinToString("")
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(MAX_NAME)
                .trim()
            return cleaned.ifEmpty { "Android" }
        }
    }
}
