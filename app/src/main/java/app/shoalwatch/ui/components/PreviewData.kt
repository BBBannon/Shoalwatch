package app.shoalwatch.ui.components

import app.shoalwatch.ui.present.CardUi
import app.shoalwatch.ui.present.EpisodeUi

/* Sample cards for @Preview functions only. No artwork loader exists in previews, so placeholders show. */

internal fun sampleCard(
    title: String,
    subtitle: String? = null,
    watched: Boolean = false,
    unwatched: Int? = null,
    progress: Float? = null,
) = CardUi(
    id = title, title = title, subtitle = subtitle, artwork = null, progress = progress,
    percentText = progress?.let { "${(it * 100).toInt()}% watched" }, watched = watched, favourite = false, unwatchedCount = unwatched,
    label = title, menuLabel = "More actions for $title",
)

internal fun sampleEpisode(title: String, watched: Boolean = false, progress: Float? = null) = EpisodeUi(
    id = title, title = title, runtime = "47 min", overview = "The crew follow the tide to a harbour that is not on any chart.",
    artwork = null, progress = progress, percentText = progress?.let { "${(it * 100).toInt()}% watched" }, watched = watched,
    label = title, menuLabel = "More actions for $title",
)

/** A sample item for previews: a film, series or episode with only the fields cards use. */
internal fun previewEntry(
    n: Int,
    name: String,
    kind: app.shoalwatch.data.library.ItemKind = app.shoalwatch.data.library.ItemKind.MOVIE,
    played: Boolean = false,
    unplayed: Int? = null,
    series: String? = null,
    code: Pair<Int, Int>? = null,
    overview: String? = null,
    percent: Float? = null,
): app.shoalwatch.data.library.ItemWithUserData {
    val id32 = "0".repeat(31)
    return app.shoalwatch.data.library.ItemWithUserData(
        app.shoalwatch.data.library.Item(
            id = id32 + n.toString(16), kind = kind, name = name, sortName = null, parentId = null,
            seriesId = series?.let { id32 + "9" }, seriesName = series, seasonId = null, indexNumber = code?.second, parentIndexNumber = code?.first,
            overview = overview, runtimeMs = 6_000_000, year = 2019, premiereDate = null, communityRating = 7.8f, officialRating = "PG",
            childCount = null, images = app.shoalwatch.data.library.ItemImages(null, null, null, null),
        ),
        app.shoalwatch.data.library.UserData(played, if (percent != null) 3_000_000 else 0, percent, if (played) 1 else 0, false, null, unplayed),
    )
}
