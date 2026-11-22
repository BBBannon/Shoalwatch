package app.shoalwatch.data.discover.api

import app.shoalwatch.core.AppError
import app.shoalwatch.core.displayText
import app.shoalwatch.data.discover.BrowseFilter
import app.shoalwatch.data.discover.DiscoverLimits
import app.shoalwatch.data.library.ListShape
import app.shoalwatch.data.library.Sort
import app.shoalwatch.data.library.api.LibraryGatewayImpl.Companion.MEDIA_QUERY
import app.shoalwatch.data.library.api.sortByWire
import app.shoalwatch.data.library.api.sortOrderWire
import app.shoalwatch.data.library.api.wire
import app.shoalwatch.data.servers.JellyfinId

/** Input the seam refuses with nothing sent: an `AppError` so a screen can show it (its wording is the screen's own). */
internal fun invalidInput() = AppError(AppError.Kind.UNKNOWN, "That filter can't be used.")

/** Only an id already in the normalised form the library uses; a dashed or upper-case one is refused, not repaired. */
internal fun normalisedId(raw: String): String =
    raw.takeIf { JellyfinId.parseOrNull(it)?.value == it } ?: throw AppError.of(AppError.Kind.SERVER_NOT_FOUND)

internal fun checkedStart(startIndex: Int): Int = startIndex.takeIf { it in 0..DiscoverLimits.MAX_START } ?: throw invalidInput()

private val LETTER = Regex("[A-Z]|#")

/** A filter value sent as one value: plain text, 1 to [max] characters, and without the comma or bar that separate several. */
internal fun filterText(raw: String, max: Int): String {
    val text = displayText(raw, max + 1)
    if (text.isEmpty() || text.codePointCount(0, text.length) > max || ',' in text || '|' in text) throw invalidInput()
    return text
}

/**
 * The whole query of one filtered list, built without any network so it can be tested alone. Every value is checked first
 * (ids normalised, year 1800 to 2200, letter A-Z or "#", text without separators); anything else is an `AppError`.
 * "#" is `nameLessThan=A`; a letter is `nameStartsWith`.
 */
internal fun browseQuery(
    userId: String,
    parentId: String,
    shape: ListShape,
    filter: BrowseFilter,
    sort: Sort,
    startIndex: Int,
): List<Pair<String, String>> = buildList {
    add("userId" to userId)
    add("parentId" to normalisedId(parentId))
    add("startIndex" to checkedStart(startIndex).toString())
    add("limit" to DiscoverLimits.PAGE.toString())
    add("sortBy" to sort.sortByWire())
    add("sortOrder" to sort.sortOrderWire())
    shape.kinds.wire().takeIf { it.isNotEmpty() }?.let { add("includeItemTypes" to it) }
    add("recursive" to shape.recursive.toString())
    add("fields" to "ChildCount")
    filter.genreId?.let { add("genreIds" to normalisedId(it)) }
    filter.studioId?.let { add("studioIds" to normalisedId(it)) }
    filter.year?.let { year -> add("years" to (year.takeIf { it in 1800..2200 } ?: throw invalidInput()).toString()) }
    filter.rating?.let { add("officialRatings" to filterText(it, DiscoverLimits.RATING)) }
    filter.tag?.let { add("tags" to filterText(it, DiscoverLimits.NAME)) }
    filter.letter?.let {
        if (!LETTER.matches(it)) throw invalidInput()
        if (it == "#") add("nameLessThan" to "A") else add("nameStartsWith" to it)
    }
    addAll(MEDIA_QUERY)
}
