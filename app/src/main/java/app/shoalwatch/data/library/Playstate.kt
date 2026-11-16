package app.shoalwatch.data.library

import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.data.servers.Session

/**
 * The tidy-up actions on watched state: take an item off Continue Watching, mark watched or unwatched, favourite.
 * What every screen calls; see "Tidy-up actions" in docs/ARCHITECTURE.md.
 *
 * Screens observe Room, so the screen updates at once. Each function saves the change on this phone (marking the rows
 * `pendingSyncAt`, so a refresh cannot undo it before the server has it) and queues it in the durable outbox in the SAME
 * transaction, then returns: nothing waits for the server, and offline an action succeeds. The outbox sends each profile's
 * changes in order, once, as that profile; when the server's own copy wins (or it refuses the item), the server's copy shows.
 *
 * A malformed id, or an item the server never showed this user, is `AppError(SERVER_NOT_FOUND)`: nothing is changed or queued.
 * A local failure (the database) is an `AppError` too, with nothing changed. The work is not abandoned if the caller's scope
 * is cancelled after it has started; cancelling stops the caller waiting, not the action.
 */
interface Playstate {
    /**
     * Marks one item (film, episode) or a whole season or series watched or unwatched, for this user. Saved rows of the
     * item (and, for a season or series, the cached episodes and seasons under it) change at once; one PLAYED change is queued
     * for the item itself (the server marks a season's or series' children).
     */
    suspend fun setPlayed(s: Session, itemId: String, played: Boolean)

    /**
     * Favourites or unfavourites an item. A list filtered by favourites is not edited here: it shows the change after its
     * next refresh (the screen can ask for one with `Library.refresh`).
     */
    suspend fun setFavourite(s: Session, itemId: String, favourite: Boolean)

    /** Removes an item from Continue Watching without marking it watched: its saved position goes back to the start (queued as POSITION 0). */
    suspend fun removeFromContinueWatching(s: Session, itemId: String)

    /**
     * Where playback stopped, for this user. [reached]: the stop report reached the server.
     * finished: watched on this phone, and a PLAYED tick queued whether or not the report reached the server (Tasks 6-8 review I1:
     * "Finished" fires at the Outro start or 90%, and the server marks a stop played only from 90%). Never a PLAY: the server may
     * count it from its own session, and the tick is absolute (`AlreadyThere` when the server has it played), so it cannot count twice.
     * !finished: the position on this phone; queued as POSITION only when !reached. [stoppedAt]: the stop's own time, given to both
     * of one stop's records: the unreached one is queued at it, and the reached one takes back only POSITION rows up to it (an
     * overlapping later stop keeps its own; Tasks 11-12 review M5). Without it, now.
     */
    suspend fun recordStop(s: Session, itemId: String, positionMs: Long, finished: Boolean, reached: Boolean = true, kick: Boolean = true, stoppedAt: Long? = null)
}

/** What a LOCAL play (a saved copy, Milestone 4) does to the saved library: implemented by [PlaystateRepository]. */
interface LocalPlaystate {
    /**
     * Same per-user queue and write gate as the other actions; sets the local value (finished: watched; otherwise the position)
     * and queues it in the same transaction: finished is one PLAY at now, otherwise a POSITION, both marked as from an offline
     * play. SERVER_NOT_FOUND for an item this user was not shown.
     */
    suspend fun recordLocalStop(s: Session, itemId: String, positionMs: Long, finished: Boolean)

    /** Watched on this phone only, nothing queued: the press in the player, whose stop's PLAY carries it (review I1). */
    suspend fun markWatchedHere(s: Session, itemId: String)
}

/** A list the server recomputes after an action, for [LibraryRepository] to refresh. Repository-internal vocabulary, not a screen API. */
internal sealed interface Recompute {
    data object Resume : Recompute
    data object NextUp : Recompute
    data class Seasons(val seriesId: String) : Recompute
    data class Episodes(val seriesId: String, val seasonId: String) : Recompute
    data class Item(val itemId: String) : Recompute
}

/** The outbox's form of a [Recompute]: "resume", "nextUp", "seasons:<id>", "episodes:<id>:<id>", "item:<id>". */
internal fun Recompute.key(): String = when (this) {
    Recompute.Resume -> "resume"
    Recompute.NextUp -> "nextUp"
    is Recompute.Seasons -> "seasons:$seriesId"
    is Recompute.Episodes -> "episodes:$seriesId:$seasonId"
    is Recompute.Item -> "item:$itemId"
}

/** The [Recompute] a key stands for; null for anything else (every id is checked again as a normalised `JellyfinId`). */
internal fun parseRecompute(key: String): Recompute? {
    val parts = key.split(':')
    val ids = parts.drop(1)
    if (ids.any { JellyfinId.parseOrNull(it)?.value != it }) return null
    return when {
        parts.size == 1 && parts[0] == "resume" -> Recompute.Resume
        parts.size == 1 && parts[0] == "nextUp" -> Recompute.NextUp
        parts.size == 2 && parts[0] == "seasons" -> Recompute.Seasons(ids[0])
        parts.size == 3 && parts[0] == "episodes" -> Recompute.Episodes(ids[0], ids[1])
        parts.size == 2 && parts[0] == "item" -> Recompute.Item(ids[0])
        else -> null
    }
}
