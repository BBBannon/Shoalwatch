package app.shoalwatch.ui.present

import app.shoalwatch.R
import app.shoalwatch.data.library.Item
import java.util.Locale

private fun joinAll(strings: Strings, parts: List<String>): String? =
    parts.filter { it.isNotBlank() }.reduceOrNull { acc, next -> strings.get(R.string.line_join, acc, next) }

/** "Season 2", "Specials" (season number 0), or the season's own name when it has no number; "Season" when it has neither. */
fun seasonLabel(strings: Strings, season: Item): String = when {
    season.indexNumber == 0 -> strings.get(R.string.season_specials)
    season.indexNumber != null -> strings.get(R.string.season_number, season.indexNumber.toString())
    season.name.isNotBlank() -> season.name
    else -> strings.get(R.string.season_unnamed)
}

/** "2019 · TV-14 · 3 seasons": whatever is known, or null when nothing is. */
fun seriesFacts(strings: Strings, series: Item, seasonCount: Int): String? = joinAll(
    strings,
    listOfNotNull(
        series.year?.toString(),
        series.officialRating?.takeIf { it.isNotBlank() },
        if (seasonCount > 0) strings.plural(R.plurals.season_count, seasonCount) else null,
    ),
)

/** A rating as text with one decimal and a point whatever the phone's language ("7.8"). */
fun ratingText(rating: Float): String = String.format(Locale.ROOT, "%.1f", rating)

/**
 * The facts under a title: [text] is "2019 · 1 hr 42 min · PG · 7.8" for the eye, [spoken] the same for a screen reader with the
 * rating said in words ("Community rating 7.8 out of 10"). Either is null when nothing is known.
 */
data class Facts(val text: String?, val spoken: String?)

fun detailFacts(strings: Strings, item: Item): Facts {
    val base = listOfNotNull(
        item.year?.toString(),
        item.runtimeMs?.takeIf { it > 0 }?.let { formatDuration(strings, it) },
        item.officialRating?.takeIf { it.isNotBlank() },
    )
    val rating = item.communityRating?.takeIf { it.isFinite() && it > 0f }
    return Facts(
        text = joinAll(strings, base + listOfNotNull(rating?.let(::ratingText))),
        spoken = joinAll(strings, base + listOfNotNull(rating?.let { strings.get(R.string.detail_rating_cd, ratingText(it)) })),
    )
}
