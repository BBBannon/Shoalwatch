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
