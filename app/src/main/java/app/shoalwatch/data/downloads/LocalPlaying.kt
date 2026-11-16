package app.shoalwatch.data.downloads

import app.shoalwatch.data.db.downloads.DownloadKey

/**
 * Which saved copies the player has open right now (memory only), so a released download's files go only once it lets go.
 * Opened by a LOCAL play's start and closed by its stop (`LocalPlaybackReports`); two players on one copy count as two holders.
 */
internal class LocalPlaying {
    private val holders = HashMap<DownloadKey, Int>()
    private val waiting = HashMap<DownloadKey, MutableList<() -> Unit>>()

    fun opened(key: DownloadKey) = synchronized(this) { holders.merge(key, 1, Int::plus); Unit }

    /** Runs every action waiting for [key] once its last holder closes. A close with no holder does nothing. */
    fun closed(key: DownloadKey) {
        val due = synchronized(this) {
            val left = (holders[key] ?: return) - 1
            if (left > 0) {
                holders[key] = left
                emptyList()
            } else {
                holders.remove(key)
                waiting.remove(key).orEmpty()
            }
        }
        due.forEach { it() }
    }

    fun isOpen(key: DownloadKey): Boolean = synchronized(this) { key in holders }

    /** Runs [action] at once if [key] is not open, else when it closes. */
    fun whenClosed(key: DownloadKey, action: () -> Unit) {
        val now = synchronized(this) {
            if (key in holders) {
                waiting.getOrPut(key) { mutableListOf() } += action
                false
            } else {
                true
            }
        }
        if (now) action()
    }
}
