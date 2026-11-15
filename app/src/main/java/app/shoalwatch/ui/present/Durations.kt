package app.shoalwatch.ui.present

import app.shoalwatch.R
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.UserData

private const val MINUTE_MS = 60_000L

/** "1 hr 42 min", "45 min", "2 hr", "Under 1 min". Rounds to the nearest minute. */
fun formatDuration(strings: Strings, ms: Long): String {
    val minutes = (ms.coerceAtLeast(0) + MINUTE_MS / 2) / MINUTE_MS
    return when {
        minutes < 1 -> strings.get(R.string.duration_under_min)
        minutes < 60 -> strings.get(R.string.duration_min, minutes.toString())
        minutes % 60 == 0L -> strings.get(R.string.duration_hr, (minutes / 60).toString())
        else -> strings.get(R.string.duration_hr_min, (minutes / 60).toString(), (minutes % 60).toString())
    }
}

/** "23 min left", or null when the length or the position is unknown, nothing has been watched, or it is finished. */
fun remainingLabel(strings: Strings, runtimeMs: Long?, positionMs: Long): String? {
    if (runtimeMs == null || runtimeMs <= 0 || positionMs <= 0 || positionMs >= runtimeMs) return null
    return strings.get(R.string.state_remaining, formatDuration(strings, runtimeMs - positionMs))
}

/** "Resume from 23 min", or null when playback never started. */
fun resumeLabel(strings: Strings, positionMs: Long): String? =
    if (positionMs > 0) strings.get(R.string.state_resume_from, formatDuration(strings, positionMs)) else null

/**
 * How much has been watched, as a whole percent from 1 to 99, or null when it is not partly watched (finished, never
 * started, or unknown). Uses the server's percentage, else the position against the length.
 */
fun percentWatched(item: Item, data: UserData?): Int? {
    if (data == null || data.played) return null
    val fromServer = data.playedPercentage?.takeIf { it.isFinite() && it > 0f }?.toDouble()
    val fromPosition = item.runtimeMs?.takeIf { it > 0 && data.positionMs > 0 }?.let { data.positionMs * 100.0 / it }
    val value = fromServer ?: fromPosition ?: return null
    return value.toInt().coerceIn(1, 99)
}

/** "S2 E5", "E5", "S2", or null. Locale-independent digits. */
/** The same as [episodeCode] for a screen reader: "Season 2, episode 5", never "S two E five". */
fun episodeSpoken(strings: Strings, item: Item): String? {
    val season = item.parentIndexNumber
    val episode = item.indexNumber
    return when {
        item.kind != ItemKind.EPISODE -> null
        season != null && episode != null -> strings.get(R.string.episode_spoken_full, season.toString(), episode.toString())
        episode != null -> strings.get(R.string.episode_spoken_episode, episode.toString())
        season != null -> strings.get(R.string.episode_spoken_season, season.toString())
        else -> null
    }
}

fun episodeCode(strings: Strings, item: Item): String? {
    val season = item.parentIndexNumber
    val episode = item.indexNumber
    return when {
        item.kind != ItemKind.EPISODE -> null
        season != null && episode != null -> strings.get(R.string.episode_code_full, season.toString(), episode.toString())
        episode != null -> strings.get(R.string.episode_code_episode, episode.toString())
        season != null -> strings.get(R.string.episode_code_season, season.toString())
        else -> null
    }
}
