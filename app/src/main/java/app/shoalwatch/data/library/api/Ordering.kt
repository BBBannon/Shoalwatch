package app.shoalwatch.data.library.api

import app.shoalwatch.data.library.ItemWithUserData

// The app never depends on the order the server happens to use: seasons and episodes are put in a fixed order here.

/** Season, then episode number (missing numbers last), then sort name (or name), then id. */
internal fun List<ItemWithUserData>.inEpisodeOrder(): List<ItemWithUserData> = sortedWith(
    compareBy<ItemWithUserData, Int?>(nullsLast()) { it.item.parentIndexNumber }
        .thenBy(nullsLast()) { it.item.indexNumber }
        .thenBy { it.item.sortName ?: it.item.name }
        .thenBy { it.item.id },
)

/** Season number order; a season without a number after the numbered ones, and season 0 (Specials) last of all. */
internal fun List<ItemWithUserData>.inSeasonOrder(): List<ItemWithUserData> = sortedWith(
    compareBy<ItemWithUserData> { group(it.item.indexNumber) }
        .thenBy { it.item.indexNumber ?: 0 }
        .thenBy { it.item.name }
        .thenBy { it.item.id },
)

private fun group(indexNumber: Int?): Int = when (indexNumber) {
    null -> 1
    0 -> 2
    else -> 0
}
