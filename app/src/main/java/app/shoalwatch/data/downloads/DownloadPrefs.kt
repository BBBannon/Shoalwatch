package app.shoalwatch.data.downloads

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** This phone's download settings (not per server, not per profile). */
data class DownloadSettings(
    val mobileData: Boolean = false,
    val storage: StorageKind = StorageKind.INTERNAL,
    val volumeId: String? = null,
    /** null = ask each time */
    val defaultQuality: DownloadQuality? = null,
)

/**
 * The device download settings in the private SharedPreferences file `download_prefs` (excluded from backup like every
 * preferences file), as `playback_prefs` holds the player's. Defaults: mobile data off (SPEC §3 row 10: Wi-Fi only), internal
 * storage, ask the quality each time. A value this version cannot read (an unknown name, a wrong type, a removable choice
 * without a valid volume id) reads as the default.
 */
class DownloadPrefs(private val prefs: SharedPreferences) {
    private val state = MutableStateFlow(load())
    val settings: StateFlow<DownloadSettings> = state.asStateFlow()

    fun setMobileData(on: Boolean) = save(state.value.copy(mobileData = on))

    /** INTERNAL keeps no volume id; REMOVABLE needs a valid one (`[A-Za-z0-9-]{1,36}`, a StorageVolume uuid). */
    fun setStorage(kind: StorageKind, volumeId: String?) {
        if (kind == StorageKind.REMOVABLE) require(volumeId != null && AndroidVolumes.VOLUME_ID.matches(volumeId)) { "A removable volume needs a valid volume id." }
        save(state.value.copy(storage = kind, volumeId = volumeId.takeIf { kind == StorageKind.REMOVABLE }))
    }

    fun setDefaultQuality(q: DownloadQuality?) = save(state.value.copy(defaultQuality = q))

    /** The notification ask (Android 13+) was shown once: never again. */
    val notificationAsked: Boolean get() = read { getBoolean(K_ASKED, false) } == true

    fun markNotificationAsked() = prefs.edit().putBoolean(K_ASKED, true).apply()

    private fun save(s: DownloadSettings) {
        prefs.edit()
            .putBoolean(K_MOBILE, s.mobileData)
            .putString(K_STORAGE, s.storage.name)
            .putString(K_VOLUME, s.volumeId)
            .putString(K_QUALITY, s.defaultQuality?.name)
            .apply()
        state.value = s
    }

    private fun load(): DownloadSettings {
        val d = DownloadSettings()
        val storage = read { getString(K_STORAGE, null) }?.let { name -> StorageKind.entries.firstOrNull { it.name == name } } ?: d.storage
        val volume = read { getString(K_VOLUME, null) }?.takeIf { AndroidVolumes.VOLUME_ID.matches(it) }
        val removable = storage == StorageKind.REMOVABLE && volume != null
        return DownloadSettings(
            mobileData = read { getBoolean(K_MOBILE, d.mobileData) } ?: d.mobileData,
            storage = if (removable) StorageKind.REMOVABLE else StorageKind.INTERNAL,
            volumeId = if (removable) volume else null,
            defaultQuality = read { getString(K_QUALITY, null) }?.let { name -> DownloadQuality.entries.firstOrNull { it.name == name } },
        )
    }

    /** A value stored with another type throws ClassCastException: that reads as "not set". */
    private fun <T> read(get: SharedPreferences.() -> T): T? = try { prefs.get() } catch (e: ClassCastException) { null }

    private companion object {
        const val K_MOBILE = "mobile_data"
        const val K_STORAGE = "storage"
        const val K_VOLUME = "volume_id"
        const val K_QUALITY = "default_quality"
        const val K_ASKED = "notification_asked"
    }
}
