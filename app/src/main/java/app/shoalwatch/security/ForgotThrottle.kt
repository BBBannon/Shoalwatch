package app.shoalwatch.security

import android.content.SharedPreferences
import app.shoalwatch.core.MonotonicClock
import app.shoalwatch.data.servers.Session

/**
 * The wait between wrong server passwords under "Forgot PIN" (Tasks 5-6 review M5), per profile. Without it a child could try
 * passwords as fast as the server answers, and Jellyfin's own lockout would then disable the parent's server account. After each
 * wrong password: 30 s, doubling, at most 15 minutes; the right one ends it.
 *
 * Kept in the private `security_prefs` file, so leaving or restarting the app does not end it. Timed only by [clock] (time since
 * boot), never the wall clock: setting the phone's clock does nothing. After a reboot the saved time is in the future of the new
 * clock, so the wait starts again in full from now (never shortened).
 */
class ForgotThrottle(private val prefs: SharedPreferences, private val clock: MonotonicClock) {
    @Synchronized fun waitSeconds(s: Session): Int {
        val failures = prefs.getInt(count(s), 0)
        if (failures <= 0) return 0
        val now = clock.nowMs()
        var at = prefs.getLong(anchor(s), now)
        if (at > now) {
            at = now
            prefs.edit().putLong(anchor(s), at).apply()
        }
        val leftMs = waitMs(failures) - (now - at)
        return if (leftMs <= 0) 0 else ((leftMs + 999) / 1000).toInt()
    }

    @Synchronized fun failed(s: Session) {
        val failures = prefs.getInt(count(s), 0) + 1
        prefs.edit().putInt(count(s), failures).putLong(anchor(s), clock.nowMs()).apply()
    }

    @Synchronized fun succeeded(s: Session) {
        prefs.edit().remove(count(s)).remove(anchor(s)).apply()
    }

    /**
     * Forgets the tries of every profile not in [kept] (security review M8 F5): run when a profile is signed out or a server is
     * removed, so no server or user id stays behind. A profile whose token was refused (401) keeps its row, so its wait stays.
     */
    @Synchronized fun keepOnly(kept: Set<Session>) {
        val keep = kept.flatMap { listOf(count(it), anchor(it)) }.toSet()
        val gone = prefs.all.keys.filter { (it.startsWith(COUNT) || it.startsWith(ANCHOR)) && it !in keep }
        if (gone.isNotEmpty()) prefs.edit().apply { gone.forEach(::remove) }.apply()
    }

    private fun count(s: Session) = "$COUNT${s.serverId}/${s.userId}"
    private fun anchor(s: Session) = "$ANCHOR${s.serverId}/${s.userId}"

    companion object {
        private const val COUNT = "forgot_failures/"
        private const val ANCHOR = "forgot_at/"
        private const val FIRST_WAIT_MS = 30_000L
        private const val LONGEST_WAIT_MS = 15 * 60_000L

        fun waitMs(failures: Int): Long =
            if (failures <= 0) 0 else (FIRST_WAIT_MS shl (failures - 1).coerceAtMost(10)).coerceAtMost(LONGEST_WAIT_MS)
    }

    override fun toString() = "ForgotThrottle(<redacted>)"
}
