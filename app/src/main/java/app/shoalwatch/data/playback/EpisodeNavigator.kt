package app.shoalwatch.data.playback

import app.shoalwatch.data.library.Freshness
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.Library
import app.shoalwatch.data.library.SeriesDetail
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The previous and next episode, from what the [Library] shows this user (so only items they may see): the neighbours in the
 * episode's season, else the last episode of the season before or the first of the season after, in the app's season order
 * (`SeriesDetail.seasons`: Specials last). Each series read is the first answer that is not `Refreshing`, waited for at most
 * [waitMs]; nothing known (a series never saved and no network) is no neighbour. A film has none.
 */
class EpisodeNavigator(private val library: Library, private val waitMs: Long = 5_000) {
    data class Around(val previous: Item?, val next: Item?)

    suspend fun around(s: Session, episode: Item): Around {
        val seriesId = episode.seriesId?.takeIf { episode.kind == ItemKind.EPISODE } ?: return NONE
        val here = read(s, seriesId, episode.seasonId) ?: return NONE
        val episodes = here.episodes.map { it.item }
        val at = episodes.indexOfFirst { it.id == episode.id }
        if (at < 0) return NONE
        val seasons = here.seasons.map { it.item.id }
        val season = seasons.indexOf(here.selectedSeasonId)
        suspend fun seasonAt(i: Int): List<Item> =
            seasons.getOrNull(i)?.takeIf { season >= 0 }?.let { id -> read(s, seriesId, id)?.episodes?.map { it.item } }.orEmpty()
        val previous = episodes.getOrNull(at - 1) ?: seasonAt(season - 1).lastOrNull()
        val next = episodes.getOrNull(at + 1) ?: seasonAt(season + 1).firstOrNull()
        return Around(previous, next)
    }

    private suspend fun read(s: Session, seriesId: String, seasonId: String?): SeriesDetail? =
        withTimeoutOrNull(waitMs) { library.series(s, seriesId, seasonId).first { it.freshness != Freshness.Refreshing } }

    private companion object {
        val NONE = Around(null, null)
    }
}
