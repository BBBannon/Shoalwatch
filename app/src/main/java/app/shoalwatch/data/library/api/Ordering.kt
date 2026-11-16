package app.shoalwatch.data.library.api

import app.shoalwatch.data.library.ItemWithUserData

// The app never depends on the order the server happens to use: seasons and episodes are put in a fixed order here.

/**
 * The one rule for where a season number goes, used by both comparators: numbered seasons (1, 2, 3...) first, then
 * anything without a season number, then season 0 (Specials) genuinely last.
 */
private fun seasonGroup(seasonNumber: Int?): Int = when (seasonNumber) {
    null -> 1
    0 -> 2
    else -> 0
}

/** Season (by [seasonGroup], then number), then episode number (missing last), then sort name (or name), then id. */
internal fun List<ItemWithUserData>.inEpisodeOrder(): List<ItemWithUserData> = sortedWith(episodeOrder)

/** Season number order by [seasonGroup]; ties by name, then id. */
internal fun List<ItemWithUserData>.inSeasonOrder(): List<ItemWithUserData> = sortedWith(
    compareBy<ItemWithUserData> { seasonGroup(it.item.indexNumber) }
        .thenBy { it.item.indexNumber ?: 0 }
        .thenBy { it.item.name }
        .thenBy { it.item.id },
)

internal val episodeOrder: Comparator<ItemWithUserData> =
    compareBy<ItemWithUserData> { seasonGroup(it.item.parentIndexNumber) }
        .thenBy { it.item.parentIndexNumber ?: 0 }
        .thenBy(nullsLast()) { it.item.indexNumber }
        .thenBy { it.item.sortName ?: it.item.name }
        .thenBy { it.item.id }
