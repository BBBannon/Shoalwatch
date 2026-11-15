package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.Session

/**
 * The tidy-up actions on watched state: take an item off Continue Watching, mark watched or unwatched, favourite.
 * What every screen calls; see "Tidy-up actions" in docs/ARCHITECTURE.md.
 *
 * Screens observe Room, so an action is "change Room, then tell the server" and the screen updates at once. Each function
 * changes the saved rows first (marking them `pendingSyncAt`, so a refresh that lands meanwhile cannot undo the change),
 * then calls the server, and returns normally only when the server has accepted it. If the server call fails, the saved
 * rows are put back exactly as they were and the [AppError] is thrown: whatever the gateway threw, unchanged.
 *
 * **Online only in this milestone.** Offline (or a lost connection) is a rolled-back failure: the gateway's own
 * `AppError` is thrown (for example `OFFLINE`: "You're offline. Showing what's saved on this phone where possible."), and the screen
 * shows that. Milestone 5 replaces the server call with a durable outbox behind this same interface: it will KEEP the
 * local change, leave the `pendingSyncAt` marker set, and queue the change instead of rolling back. The marker is the hook.
 *
 * A malformed id, or an item the server never showed this user, is `AppError(SERVER_NOT_FOUND)`: nothing is changed or sent.
 * The work (change, server call, clean-up) is not abandoned if the caller's scope is cancelled after the change is made;
 * cancelling stops the caller waiting, not the action. One action at a time runs per item and user: a second for the same item waits.
 */
interface Playstate {
    /**
     * Marks one item (film, episode) or a whole season or series watched or unwatched, for this user. Saved rows of the
     * item (and, for a season or series, the cached episodes and seasons under it) change at once; the server's Continue
     * Watching and Next Up are asked again afterwards.
     */
    suspend fun setPlayed(s: Session, itemId: String, played: Boolean)

    /**
     * Favourites or unfavourites an item. A list filtered by favourites is not edited here: it shows the change after its
     * next refresh (the screen can ask for one with `Library.refresh`).
     */
    suspend fun setFavourite(s: Session, itemId: String, favourite: Boolean)

    /** Removes an item from Continue Watching without marking it watched: its saved position goes back to the start. */
    suspend fun removeFromContinueWatching(s: Session, itemId: String)

    /**
     * Saves where playback stopped, on this phone, for this user. finished = true is setPlayed(true) (local change, server call,
     * rollback on failure, as today). finished = false sets the saved position only (no server call: the stop report carried it),
     * noted in LocalChangeLog so an older refresh cannot undo it, then refreshes resume and nextUp. Same per-user queue and write
     * gate as the other actions. Never called for an item this user was not shown (SERVER_NOT_FOUND, nothing changed).
     */
    suspend fun recordStop(s: Session, itemId: String, positionMs: Long, finished: Boolean)
}

/** A list the server recomputes after an action, for [LibraryRepository] to refresh. Repository-internal vocabulary, not a screen API. */
internal sealed interface Recompute {
    data object Resume : Recompute
    data object NextUp : Recompute
    data class Seasons(val seriesId: String) : Recompute
    data class Episodes(val seriesId: String, val seasonId: String) : Recompute
    data class Item(val itemId: String) : Recompute
}
