package app.shoalwatch.ui.present

import app.shoalwatch.R
import app.shoalwatch.data.images.ArtworkRef
import app.shoalwatch.data.images.backdropArtwork
import app.shoalwatch.data.images.primaryArtwork
import app.shoalwatch.data.images.thumbArtwork
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.LibraryKind
import app.shoalwatch.data.servers.Session

/**
 * What a card shows, worked out once from an item. [title] is never empty ("Untitled" stands in for a nameless item).
 * [label] is the whole card as one spoken line ("The Example Film, 2019, watched"); [menuLabel] names the separate "more
 * actions" button. [progress] (0 to 1) and [percentText] describe a part-watched item, so the bar is never the only signal.
 */
data class CardUi(
    val id: String,
    val title: String,
    val subtitle: String?,
    val artwork: ArtworkRef?,
    val progress: Float?,
    val percentText: String?,
    val watched: Boolean,
    val favourite: Boolean,
    val unwatchedCount: Int?,
    val label: String,
    val menuLabel: String,
)

/** The name to show for an item. */
fun Item.displayName(strings: Strings): String = name.ifBlank { strings.get(R.string.untitled) }

/** The title of the actions sheet: an episode says which series and which episode ("Harbour Lights · S2 E2 · Episode 2"); anything else its name. */
fun Item.sheetTitle(strings: Strings): String =
    if (kind == ItemKind.EPISODE) joinAll(strings, listOfNotNull(seriesName, episodeCode(strings, this), name)) ?: displayName(strings) else displayName(strings)

private fun join(strings: Strings, first: String?, second: String?): String? = when {
    first == null -> second
    second == null -> first
    else -> strings.get(R.string.line_join, first, second)
}

private fun speak(parts: List<String?>): String = parts.filterNotNull().filter { it.isNotBlank() }.joinToString(", ")

/** A poster card: title, year, a watched tick for a watched film or series, an unwatched count for a series. */
fun posterCard(entry: ItemWithUserData, session: Session, strings: Strings): CardUi {
    val item = entry.item
    val data = entry.userData
    val title = item.displayName(strings)
    val year = item.year?.toString()
    val watched = data?.played == true
    val unwatched = if (item.kind == ItemKind.SERIES && !watched) data?.unplayedItemCount?.takeIf { it > 0 } else null
    return CardUi(
        id = item.id, title = title, subtitle = year, artwork = item.primaryArtwork(session),
        progress = null, percentText = null, watched = watched, favourite = data?.favourite == true, unwatchedCount = unwatched,
        label = speak(
            listOf(
                title, year,
                if (watched) strings.get(R.string.state_watched) else null,
                unwatched?.let { strings.plural(R.plurals.unwatched_episodes, it) },
                if (data?.favourite == true) strings.get(R.string.state_favourite) else null,
            ),
        ),
        menuLabel = strings.get(R.string.cd_more_actions, title),
    )
}

/**
 * A wide card for Continue Watching and Next Up. An episode shows the series name over "S2 E5 · Episode title"; a film shows
 * its title over the time left (or the year).
 */
fun wideCard(entry: ItemWithUserData, session: Session, strings: Strings): CardUi {
    val item = entry.item
    val data = entry.userData
    val name = item.displayName(strings)
    val isEpisode = item.kind == ItemKind.EPISODE
    val title = if (isEpisode) item.seriesName?.ifBlank { null } ?: name else name
    val subtitle = if (isEpisode) join(strings, episodeCode(strings, item), name.takeIf { item.name.isNotBlank() }) else
        remainingLabel(strings, item.runtimeMs, data?.positionMs ?: 0) ?: item.year?.toString()
    val percent = percentWatched(item, data)
    val percentText = percent?.let { strings.get(R.string.state_percent_watched, it.toString()) }
    val watched = data?.played == true
    return CardUi(
        id = item.id, title = title, subtitle = subtitle, artwork = wideArtwork(item, session),
        progress = percent?.let { it / 100f }, percentText = percentText, watched = watched, favourite = data?.favourite == true,
        unwatchedCount = null,
        label = speak(
            listOf(
                title,
                if (isEpisode) episodeSpoken(strings, item) else null,
                if (isEpisode) name.takeIf { item.name.isNotBlank() } else subtitle,
                percentText,
                if (watched) strings.get(R.string.state_watched) else null,
            ),
        ),
        menuLabel = strings.get(R.string.cd_more_actions, title),
    )
}

/** Landscape artwork for a wide slot: an episode prefers its own still, anything else its thumb then backdrop. */
fun wideArtwork(item: Item, session: Session): ArtworkRef? =
    if (item.kind == ItemKind.EPISODE) item.thumbArtwork(session) ?: item.primaryArtwork(session) ?: item.backdropArtwork(session)
    else item.thumbArtwork(session) ?: item.backdropArtwork(session) ?: item.primaryArtwork(session)

/** One episode in a season list. [overview] is plain text. */
data class EpisodeUi(
    val id: String,
    val title: String,
    val runtime: String?,
    val overview: String,
    val artwork: ArtworkRef?,
    val progress: Float?,
    val percentText: String?,
    val watched: Boolean,
    val label: String,
    val menuLabel: String,
)

fun episodeRow(entry: ItemWithUserData, session: Session, strings: Strings): EpisodeUi {
    val item = entry.item
    val data = entry.userData
    val name = item.displayName(strings)
    val number = item.indexNumber?.let { strings.get(R.string.episode_code_episode, it.toString()) }
    val spokenNumber = item.indexNumber?.let { strings.get(R.string.episode_spoken_episode, it.toString()) }
    val title = join(strings, number, name) ?: name
    val runtime = item.runtimeMs?.takeIf { it > 0 }?.let { formatDuration(strings, it) }
    val percent = percentWatched(item, data)
    val percentText = percent?.let { strings.get(R.string.state_percent_watched, it.toString()) }
    val watched = data?.played == true
    return EpisodeUi(
        id = item.id, title = title, runtime = runtime, overview = plainOverview(item.overview), artwork = wideArtwork(item, session),
        progress = percent?.let { it / 100f }, percentText = percentText, watched = watched,
        label = speak(listOf(spokenNumber, name, runtime, percentText, if (watched) strings.get(R.string.state_watched) else null)),
        menuLabel = strings.get(R.string.cd_more_actions, name),
    )
}

/** A compact search result: artwork, title, and the year (a film or series) or "Series name · S1 E2" (an episode). */
data class SearchRowUi(val id: String, val title: String, val subtitle: String?, val artwork: ArtworkRef?, val label: String)

fun searchRow(entry: ItemWithUserData, session: Session, strings: Strings): SearchRowUi {
    val item = entry.item
    val title = item.displayName(strings)
    val subtitle = when (item.kind) {
        ItemKind.EPISODE -> join(strings, item.seriesName?.ifBlank { null }, episodeCode(strings, item))
        ItemKind.SERIES -> join(strings, strings.get(R.string.search_series_label), item.year?.toString())
        else -> item.year?.toString()
    }
    val spoken = when (item.kind) {
        ItemKind.EPISODE -> listOf(title, item.seriesName?.ifBlank { null }, episodeSpoken(strings, item))
        else -> listOf(title, subtitle)
    }
    return SearchRowUi(item.id, title, subtitle, item.primaryArtwork(session), speak(spoken))
}

/** "Films", "TV shows"... */
fun LibraryKind.label(strings: Strings): String = strings.get(
    when (this) {
        LibraryKind.MOVIES -> R.string.library_kind_movies
        LibraryKind.TV -> R.string.library_kind_tv
        LibraryKind.BOX_SETS -> R.string.library_kind_collections
        LibraryKind.MIXED -> R.string.library_kind_mixed
    },
)

/** "412 films" for a films library, "12 series", else "N items"; null when the total is unknown. */
fun totalLabel(strings: Strings, kind: LibraryKind?, total: Int?): String? {
    if (total == null || total < 0) return null
    return strings.plural(
        when (kind) {
            LibraryKind.MOVIES -> R.plurals.total_films
            LibraryKind.TV -> R.plurals.total_series
            else -> R.plurals.total_items
        },
        total,
    )
}
