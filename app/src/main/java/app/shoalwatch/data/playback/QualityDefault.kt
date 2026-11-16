package app.shoalwatch.data.playback

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager

/**
 * The quality a film or episode starts at (final review I-2; plan "Decisions made", research complaint 15): a device setting,
 * not per user and not a secret, in the private prefs file subtitle appearance uses. The quality last chosen in the player's
 * Quality sheet; with none chosen, 8 Mbps on a metered network (mobile data, a phone's hotspot) and the best otherwise.
 * [metered] is asked at each start, so a phone that has moved from Wi-Fi to mobile data starts the next film at 8 Mbps.
 */
class QualityDefault(private val prefs: SharedPreferences, private val metered: () -> Boolean) {
    fun start(): Quality = saved() ?: if (metered()) Quality.MBPS_8 else Quality.MAXIMUM

    /** The user's own choice in the Quality sheet: the start for every later film, on any network. */
    fun choose(quality: Quality) {
        prefs.edit().putString(KEY, quality.name).apply()
    }

    private fun saved(): Quality? = prefs.getString(KEY, null)?.let { name -> Quality.entries.firstOrNull { it.name == name } }

    companion object {
        const val KEY = "default_quality"

        /**
         * Whether the active network is metered (`ACCESS_NETWORK_STATE`, already declared). No connectivity service, or a
         * failure asking it, counts as metered: the cautious answer for someone's data allowance.
         */
        fun activeNetworkMetered(context: Context): Boolean = try {
            context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: true
        } catch (e: RuntimeException) {
            true
        }
    }
}
