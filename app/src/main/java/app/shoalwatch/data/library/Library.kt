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

/** The home screen: the user's libraries, what to resume, what is next up, and the latest in each library (in library order). */
data class Home(val views: List<LibraryView>, val resume: HomeRow, val nextUp: HomeRow, val latest: List<Pair<LibraryView, HomeRow>>)

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

/** [item] is null until the item has been saved (never seen, or the first fetch has not finished or failed; see [freshness]). */
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

        /** What to ask for when opening a library: films under a films library, series under a TV library, collections, or everything one level down. */
        fun forLibrary(kind: LibraryKind): ListShape = when (kind) {
            LibraryKind.MOVIES -> ListShape(setOf(ItemKind.MOVIE), recursive = true)
            LibraryKind.TV -> ListShape(setOf(ItemKind.SERIES), recursive = true)
            LibraryKind.BOX_SETS -> ListShape(setOf(ItemKind.BOX_SET), recursive = true)
            LibraryKind.MIXED -> DIRECT
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
 * Only models from this package appear here (no DTOs, Room entities or HTTP types). The suspend functions may throw
 * [AppError] ([search] only); [loadMore] and [refresh] report failure through the flows' [Freshness] instead.
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
}
