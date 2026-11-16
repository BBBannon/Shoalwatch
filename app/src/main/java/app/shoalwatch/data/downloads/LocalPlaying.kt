package app.shoalwatch.data.downloads

import app.shoalwatch.data.db.downloads.DownloadKey

/**
 * Which saved copies the player has open right now (memory only), so a released download's files go only once it lets go.
 * Opened by a LOCAL play's start and closed by its stop (`LocalPlaybackReports`); two players on one copy count as two holders.
 */
internal class LocalPlaying {
    private val holders = HashMap<DownloadKey, Int>()
    private val waiting = HashMap<DownloadKey, MutableList<() -> Unit>>()

    /** "Watched — delete download" pressed while the copy was open, per profile: the coming stop is a finished watch (review I1). */
    private val watched = HashSet<Pair<String, DownloadKey>>()

    /** Stops under way, per profile: taken their press and not yet closed. A press now goes the normal way (Tasks 13-14 review M1). */
    private val stopping = HashMap<Pair<String, DownloadKey>, Int>()

    fun opened(key: DownloadKey) = synchronized(this) { holders.merge(key, 1, Int::plus); Unit }

    /**
     * Runs every action waiting for [key] once its last holder closes. A close with no holder does nothing. [userId]: the stop
     * that [takeWatched] for that profile has ended.
     */
    fun closed(key: DownloadKey, userId: String? = null) {
        val due = synchronized(this) {
            if (userId != null) {
                val k = userId to key
                val n = (stopping[k] ?: 0) - 1
                if (n > 0) stopping[k] = n else stopping.remove(k)
            }
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

    /**
     * The press in the player (Tasks 11-12 review I1): true, and remembered for [userId], only while [key] is open, in one step,
     * so the copy cannot close between the check and the mark. The stop's own PLAY then carries the watch to the server.
     * False while this profile's stop is saving (it has taken its press already): the press then goes the normal way.
     */
    fun watchedWhileOpen(userId: String, key: DownloadKey): Boolean = synchronized(this) {
        if (key !in holders || (userId to key) in stopping) return false
        watched += userId to key
        true
    }

    /** The stop: whether this profile pressed "watched" during the play (and forgets it); until [closed], no press is remembered. */
    fun takeWatched(userId: String, key: DownloadKey): Boolean = synchronized(this) {
        stopping.merge(userId to key, 1, Int::plus)
        watched.remove(userId to key)
    }

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
