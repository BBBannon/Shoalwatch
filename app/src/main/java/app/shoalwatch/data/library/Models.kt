package app.shoalwatch.data.library

import java.time.Instant

/**
 * What an item is, as far as the app cares. Anything else the server sends is [OTHER]. Milestone 11 added [PHOTO], [VIDEO] (a home
 * video or any plain `Video`) and [PLAYLIST]; kinds are stored by name, so an older row simply never has them.
 */
enum class ItemKind { MOVIE, SERIES, SEASON, EPISODE, BOX_SET, FOLDER, OTHER, PHOTO, VIDEO, PLAYLIST }

/**
 * The kinds of library the app shows. Milestone 11 added [PHOTOS] and [HOME_VIDEOS]; libraries of any other kind (music, books,
 * live TV...) are still not offered.
 */
enum class LibraryKind { MOVIES, TV, BOX_SETS, MIXED, PHOTOS, HOME_VIDEOS }

/**
 * One of the user's libraries. [id] is a normalised `JellyfinId`; [name] is safe to display, or empty when the server
 * gave no usable name (the UI supplies the placeholder, so no English text lives here).
 */
data class LibraryView(val id: String, val name: String, val kind: LibraryKind)

/**
 * An item as the app sees it: every id validated and normalised, every string safe to display (no control, bidi or
 * zero-width characters, length-capped), every number range-checked, every image tag a plain alphanumeric token.
 * An empty [name] means the server gave no usable name; the UI supplies the placeholder. [runtimeMs] is milliseconds. [overview] is plain text with line breaks; it may contain HTML, which the UI shows as text.
 */
data class Item(
    val id: String,
    val kind: ItemKind,
    val name: String,
    val sortName: String?,
    val parentId: String?,
    val seriesId: String?,
    val seriesName: String?,
    val seasonId: String?,
    val indexNumber: Int?,
    val parentIndexNumber: Int?,
    val overview: String?,
    val runtimeMs: Long?,
    val year: Int?,
    val premiereDate: Instant?,
    val communityRating: Float?,
    val officialRating: String?,
    val childCount: Int?,
    val images: ItemImages,
)

/** Image tags (cache-busting tokens, `[A-Za-z0-9]{1,64}`); null when the item has no such image. */
data class ItemImages(val primaryTag: String?, val backdropTag: String?, val thumbTag: String?, val seriesPrimaryTag: String?)

/** What this user has done with an item. [positionMs] is where playback stopped (0 if nowhere). */
data class UserData(
    val played: Boolean,
    val positionMs: Long,
    val playedPercentage: Float?,
    val playCount: Int,
    val favourite: Boolean,
    val lastPlayed: Instant?,
    val unplayedItemCount: Int?,
)

/** [userData] is null when the server sent none. */
data class ItemWithUserData(val item: Item, val userData: UserData?)

/**
 * One page of a list. [items] holds only the KEPT elements (the gateway skips malformed ones and removes repeats), so
 * `items.size` is NOT how far the server's list was read: [nextStartIndex] is (`startIndex` plus every element the server
 * sent in this response). [total] is the server's count when it gave a usable one (at most one million), else null.
 */
data class Page<T>(val items: List<T>, val startIndex: Int, val total: Int?, val nextStartIndex: Int, val endReached: Boolean)

enum class SortBy { NAME, DATE_ADDED, RELEASE_DATE, RATING }

data class Sort(val by: SortBy, val descending: Boolean) {
    /** Stable, `[a-z0-9_]` only: part of cache list keys. */
    val key: String get() = by.name.lowercase() + if (descending) "_desc" else "_asc"
}

data class ItemFilter(val unwatchedOnly: Boolean = false, val favouritesOnly: Boolean = false) {
    /** Stable, `[a-z0-9_]` only: part of cache list keys. */
    val key: String
        get() = listOfNotNull("unwatched".takeIf { unwatchedOnly }, "favourites".takeIf { favouritesOnly }).joinToString("_").ifEmpty { "all" }
}
