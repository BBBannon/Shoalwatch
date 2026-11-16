package app.shoalwatch.data.library

import app.shoalwatch.data.library.api.episodeOrder

/**
 * Which season a series opens on when none was asked for: the season holding the next unwatched episode, when the saved
 * episodes show one (Specials are skipped: an unwatched special should not decide); else the first numbered season; else
 * Specials; else the first season there is; else null (no season is known yet).
 */
internal fun selectSeason(seasons: List<ItemWithUserData>, savedEpisodes: List<ItemWithUserData>): String? {
    val known = seasons.mapTo(HashSet()) { it.item.id }
    val next = savedEpisodes.sortedWith(episodeOrder).firstOrNull {
        it.item.parentIndexNumber != 0 && it.userData?.played != true && it.item.seasonId in known
    }
    return next?.item?.seasonId
        ?: seasons.firstOrNull { (it.item.indexNumber ?: 0) > 0 }?.item?.id
        ?: seasons.firstOrNull { it.item.indexNumber == 0 }?.item?.id
        ?: seasons.firstOrNull()?.item?.id
}
