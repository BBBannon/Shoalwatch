package app.shoalwatch.data.playback

import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.data.servers.Session
import java.security.SecureRandom
import java.util.Base64
import kotlin.random.Random

/**
 * An in-memory play queue (a playlist's playable entries, in order or shuffled once on the phone): item ids only, each once, at
 * most [MAX_ITEMS]. While one is in force the player's Up next, Next and Previous follow it instead of the series: no next at the
 * last item, no previous at the first (Previous then restarts the item, as with no neighbour). Never persisted: after the
 * process ends its token is unknown ([PlayQueues.get]) and the item plays alone. `PlaybackController.open` takes it (null keeps the
 * one already set, as on a retry) with the version (`mediaSourceId`) chosen for that one play; `EpisodeNavigator.around` turns it
 * into the neighbours the controller already offers.
 */
class PlayQueue private constructor(val ids: List<String>) {
    fun contains(id: String) = id in ids

    /** The id after [id], or null at the last item or when [id] is not in the queue. */
    fun next(id: String): String? = ids.indexOf(id).takeIf { it >= 0 }?.let { ids.getOrNull(it + 1) }

    /** The id before [id], or null at the first item or when [id] is not in the queue. */
    fun previous(id: String): String? = ids.indexOf(id).takeIf { it >= 0 }?.let { ids.getOrNull(it - 1) }

    override fun toString() = "PlayQueue(${ids.size} items)"

    companion object {
        const val MAX_ITEMS = 2_000

        /**
         * The playable [items] (films, episodes, videos) with a valid id, a repeated id kept once (its first place), the first
         * [MAX_ITEMS]; shuffled once with [random] when [shuffle]. Null when none is playable.
         */
        fun of(items: List<Item>, shuffle: Boolean, random: Random = Random.Default): PlayQueue? {
            val ids = items.asSequence()
                .filter { it.kind == ItemKind.MOVIE || it.kind == ItemKind.EPISODE || it.kind == ItemKind.VIDEO }
                .map { it.id }
                .filter { JellyfinId.parseOrNull(it)?.value == it }
                .distinct()
                .take(MAX_ITEMS)
                .toList()
            if (ids.isEmpty()) return null
            return PlayQueue(if (shuffle) ids.shuffled(random) else ids)
        }
    }
}

/**
 * The app-scoped registry of live queues, each under a random 128-bit URL-safe token (so a route can carry it without carrying
 * ids, and one cannot be guessed). At most [maxQueues] are kept, the least recently used going first. An unknown or malformed
 * token is null.
 */
class PlayQueues(private val maxQueues: Int = 8, private val random: SecureRandom = SecureRandom()) {
    private class Held(val session: Session, val queue: PlayQueue)

    private val queues = object : LinkedHashMap<String, Held>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Held>) = size > maxQueues
    }

    /** Held for [session] (a server and a user) alone: a token made for another profile is unknown to this one. */
    @Synchronized
    fun put(session: Session, queue: PlayQueue): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        queues[token] = Held(session, queue)
        return token
    }

    @Synchronized
    fun get(session: Session, token: String?): PlayQueue? =
        token?.takeIf { TOKEN.matches(it) }?.let { queues[it] }?.takeIf { it.session == session }?.queue

    /**
     * The active session changed (sign-out, profile switch): everything held for any other session (or for nobody) goes. A lock does
     * not change the session, so it keeps the queues: they belong to the same profile, and the lock reset leaves no route holding a token.
     */
    @Synchronized
    fun keepOnly(session: Session?) { queues.values.removeAll { it.session != session } }

    private companion object {
        val TOKEN = Regex("[A-Za-z0-9_-]{22}")
    }
}
