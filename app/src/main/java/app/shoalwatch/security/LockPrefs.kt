package app.shoalwatch.security

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class LockAfter(val ms: Long) { IMMEDIATELY(0), ONE_MINUTE(60_000), FIVE_MINUTES(300_000), FIFTEEN_MINUTES(900_000) }

data class LockSettings(val appLock: Boolean = false, val lockAfter: LockAfter = LockAfter.ONE_MINUTE, val hideInRecents: Boolean = false)

/** The device's app lock settings in the private `security_prefs` file. A value this version cannot read is the default. */
class LockPrefs(private val prefs: SharedPreferences) {
    private val state = MutableStateFlow(read())
    val settings: StateFlow<LockSettings> = state.asStateFlow()

    fun setAppLock(on: Boolean) = write { putBoolean(APP_LOCK, on) }

    fun setLockAfter(value: LockAfter) = write { putString(LOCK_AFTER, value.name) }

    fun setHideInRecents(on: Boolean) = write { putBoolean(HIDE_IN_RECENTS, on) }

    @Synchronized private fun write(edit: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply(edit).apply()
        state.value = read()
    }

    private fun read(): LockSettings {
        val defaults = LockSettings()
        return LockSettings(
            appLock = bool(APP_LOCK, defaults.appLock),
            lockAfter = string(LOCK_AFTER)?.let { name -> LockAfter.entries.firstOrNull { it.name == name } } ?: defaults.lockAfter,
            hideInRecents = bool(HIDE_IN_RECENTS, defaults.hideInRecents),
        )
    }

    private fun bool(key: String, default: Boolean) = try {
        prefs.getBoolean(key, default)
    } catch (_: ClassCastException) {
        default
    }

    private fun string(key: String) = try {
        prefs.getString(key, null)
    } catch (_: ClassCastException) {
        null
    }

    override fun toString() = "LockPrefs(<redacted>)"

    private companion object {
        const val APP_LOCK = "app_lock"
        const val LOCK_AFTER = "lock_after"
        const val HIDE_IN_RECENTS = "hide_in_recents"
    }
}
