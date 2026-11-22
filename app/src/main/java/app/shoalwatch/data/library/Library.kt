package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.flow.Flow

/**
 * How current a list is. Whatever the state, the saved rows are shown.
 *  - [Fresh]: loaded within its window and nothing is running.
 *  - [Refreshing]: a refresh (or a "load more") is running now.
 *  - [Stale]: the last attempt failed with [reason]; the saved copy is what is on screen. `UNAUTHORISED` and
 *    `NOT_SIGNED_IN` are reported the same way so the screen can offer to sign in again; nothing is deleted.
 */
sealed interface Freshness {
    data object Fresh : Freshness
    data object Refreshing : Freshness
    data class Stale(val reason: AppError.Kind) : Freshness
}

/**
 * A page-by-page list. [total] is the server's count when it gave a usable one. [items] can be empty while [canLoadMore]
 * is true (a page whose elements were all unusable); only [canLoadMore] says whether there is more.
 */
data class Listing(val items: List<ItemWithUserData>, val total: Int?, val freshness: Freshness, val canLoadMore: Boolean)

/** One row of the home screen: the saved items and how current they are. */
data class HomeRow(val items: List<ItemWithUserData>, val freshness: Freshness)

/** The home screen: the user's libraries (with how current that list is), what to resume, what is next up, and the latest in each library (in library order). */
data class Home(val views: List<LibraryView>, val viewsFreshness: Freshness, val resume: HomeRow, val nextUp: HomeRow, val latest: List<Pair<LibraryView, HomeRow>>)

/**
 * A series with its seasons and the episodes of the selected season. [selectedSeasonId] is the requested season, else the
 * one holding the next unwatched episode (when the saved copy shows it), else the first numbered season, else Specials.
 * It is null only while no season is known, and [episodes] is empty then. [freshness] covers all three parts together.
 */
data class SeriesDetail(
    val series: ItemWithUserData?,
    val seasons: List<ItemWithUserData>,
    val episodes: List<ItemWithUserData>,
    val selectedSeasonId: String?,
    val freshness: Freshness,
)

/**
 * [item] is null until the item has been saved for this user (never shown to them, or the first fetch has not finished or
 * failed; see [freshness]). When the server says the item is not there, or not for this user (404 or 403), it is revoked for this
 * user: [item] becomes null with `Stale(SERVER_NOT_FOUND)` and it also leaves their lists. The item disappears for this user; the
 * screen should say it is no longer available. Other users are unaffected.
 */
data class ItemDetail(val item: ItemWithUserData?, val freshness: Freshness)

/** [offline] is true when the server could not be reached and [items] are only what is saved on the phone. */
data class SearchResult(val items: List<ItemWithUserData>, val offline: Boolean)

/** What a listing contains: [kinds] (empty: every kind) and whether to look at every level below ([recursive]) or only direct children. */
data class ListShape(val kinds: Set<ItemKind>, val recursive: Boolean) {
    /** Stable, `[a-z0-9_]` only: part of cache list keys. */
    val key: String
        get() = (if (kinds.isEmpty()) "any" else kinds.map { it.name.lowercase() }.sorted().joinToString("_")) + if (recursive) "_r" else "_d"

    companion object {
        /** Direct children of any kind: a folder, a collection's contents, a mixed library. */
        val DIRECT = ListShape(emptySet(), recursive = false)

        /**
         * What to ask for when opening a library: films under a films library, series under a TV library, collections, or everything
         * one level down (a mixed library, and photo and home-video libraries, whose albums and folders open like a collection).
         */
        fun forLibrary(kind: LibraryKind): ListShape = when (kind) {
            LibraryKind.MOVIES -> ListShape(setOf(ItemKind.MOVIE), recursive = true)
            LibraryKind.TV -> ListShape(setOf(ItemKind.SERIES), recursive = true)
            LibraryKind.BOX_SETS -> ListShape(setOf(ItemKind.BOX_SET), recursive = true)
            LibraryKind.MIXED, LibraryKind.PHOTOS, LibraryKind.HOME_VIDEOS -> DIRECT
        }
    }
}

/** What a pull-to-refresh refreshes. */
sealed interface RefreshTarget {
    data object Home : RefreshTarget
    data class Children(val parentId: String, val shape: ListShape, val sort: Sort, val filter: ItemFilter) : RefreshTarget
    data class Series(val seriesId: String, val seasonId: String? = null) : RefreshTarget
    data class Item(val itemId: String) : RefreshTarget
}

/**
 * What every browsing screen uses. Collecting a flow emits what is saved at once (even if empty), then refreshes in the
 * background when the list was never loaded or is older than its window; collecting never waits for the network, and a
 * refresh that has begun finishes and is saved even if the collector leaves. Flows never throw because of the network or the
 * server: a failed refresh shows as [Freshness.Stale]. Everything is for one [Session]; two users on a server never see each other's lists or watched state.
 *
 * A malformed id never throws: a flow for it emits one empty result that is `Stale(SERVER_NOT_FOUND)`, and [loadMore] and
 * [refresh] do nothing. A user is shown only items the server has returned to that user on this device, re-confirmed within the last 30 days (or downloaded, or in one of their current lists). An item the server later refuses for that user is hidden from them at once.
 *
 * Guidance for screens: pass the `Session` into every call and restart the flow when the account changes; collecting refreshes
 * what is due only when the flow starts (and a list that just failed is not retried for 30 seconds; only [refresh] always
 * tries), so a screen that keeps collecting while off screen (Home) asks again on its return with [refreshHomeIfDue]; [Listing.items] can be empty while [Listing.canLoadMore] is true, so load more rather than show
 * "empty", and call [loadMore] again when freshness returns to `Fresh` while the end of the list is still on screen; never
 * read the DAOs or `LibraryStore` from UI code.
 *
 * Only models from this package appear here (no DTOs, Room entities or HTTP types). Failures of the server or network never
 * throw from [loadMore] or [refresh]: they show through the flows' [Freshness]; [search] throws an [AppError] for those. A local
 * failure (the database) in any of the three is thrown as `AppError(UNKNOWN)`, never as a raw exception.
 */
interface Library {
    fun home(s: Session): Flow<Home>

    fun children(s: Session, parentId: String, shape: ListShape, sort: Sort, filter: ItemFilter): Flow<Listing>

    /** Fetches the next page of a [children] list. Does nothing while a refresh or another load is running, or when all is loaded. */
    suspend fun loadMore(s: Session, parentId: String, shape: ListShape, sort: Sort, filter: ItemFilter)

    fun series(s: Session, seriesId: String, seasonId: String? = null): Flow<SeriesDetail>

    fun item(s: Session, itemId: String): Flow<ItemDetail>

    /** Online: the server's results (also saved, so a detail screen opens at once). Offline: saved names that contain [term]. Blank: nothing, and no request. */
    suspend fun search(s: Session, term: String): SearchResult

    /** Pull-to-refresh: joins a refresh already running for the same list, and returns when it is done (success or not). */
    suspend fun refresh(s: Session, target: RefreshTarget)

    /**
     * Coming back to Home (round 3, 1.1.0): asks again for each of Home's lists that is older than its window (2 minutes), as a
     * first collection does; a list within its window, or one that failed in the last 30 s, is not asked (so going back and forth
     * makes no burst of requests). Does not wait for the refreshes. A failure shows as the lists' freshness.
     */
    suspend fun refreshHomeIfDue(s: Session) {}

    /** Something changed on the server that no window can know about (a library scan finished): Home's lists are due now. */
    suspend fun markHomeDue(s: Session) {}

    /**
     * Milestone 10: [s] deleted [itemId] from the server. It leaves every list of [s] at once. With [keepRecord] (a download kept on
     * this phone) its record stays, so Downloads still shows it; otherwise it is gone for [s] as an item the server refused is.
     * Other profiles and the shared item row are untouched (their next refresh drops it). A malformed id does nothing.
     */
    suspend fun forgetDeleted(s: Session, itemId: String, keepRecord: Boolean) {}
}
