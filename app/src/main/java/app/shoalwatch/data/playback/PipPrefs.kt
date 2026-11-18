package app.shoalwatch.data.playback

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Keep playing in a small window when you leave the app" (Milestone 7 Task 9): a device setting, not per user and not a secret,
 * in the private `playback_prefs` file (excluded from backup like every preferences file). On unless turned off; a value this
 * version cannot read is on. It decides only whether leaving the app enters picture-in-picture by itself: the player's own
 * button works either way.
 */
class PipPrefs(private val prefs: SharedPreferences) {
    private val state = MutableStateFlow(read())
    val autoEnter: StateFlow<Boolean> = state.asStateFlow()

    fun setAutoEnter(on: Boolean) {
        prefs.edit().putBoolean(KEY, on).apply()
        state.value = on
    }

    private fun read(): Boolean = try {
        prefs.getBoolean(KEY, true)
    } catch (e: ClassCastException) {
        true
    }

    companion object {
        const val KEY = "pip_auto"
    }
}
