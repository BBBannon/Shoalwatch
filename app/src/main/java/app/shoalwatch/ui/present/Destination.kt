package app.shoalwatch.ui.present

import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData

/** Where tapping an item goes. Plain ids only. */
sealed interface Destination {
    /** A series, optionally at a season with one episode picked out. */
    data class Series(val seriesId: String, val seasonId: String? = null, val episodeId: String? = null) : Destination

    data class Detail(val itemId: String) : Destination

    /** A collection or folder, opened like a library. */
    data class Library(val parentId: String) : Destination
}

/**
 * Series open the series screen, an episode opens its series at its season with itself highlighted, films open detail,
 * collections and folders open as a library.
 */
fun ItemWithUserData.destination(): Destination {
    val i = item
    val series = i.seriesId
    return when (i.kind) {
        ItemKind.SERIES -> Destination.Series(i.id)
        ItemKind.EPISODE -> if (series != null) Destination.Series(series, i.seasonId ?: i.parentId, i.id) else Destination.Detail(i.id)
        ItemKind.SEASON -> if (series != null) Destination.Series(series, i.id) else Destination.Detail(i.id)
        ItemKind.BOX_SET, ItemKind.FOLDER -> Destination.Library(i.id)
        ItemKind.MOVIE, ItemKind.OTHER -> Destination.Detail(i.id)
    }
}

/** "Details" in the actions sheet: a series opens its page, a collection or folder opens as a library, anything else its detail page. */
fun ItemWithUserData.detailsDestination(): Destination = when (item.kind) {
    ItemKind.SERIES -> Destination.Series(item.id)
    ItemKind.BOX_SET, ItemKind.FOLDER -> Destination.Library(item.id)
    else -> Destination.Detail(item.id)
}
