package app.shoalwatch.ui.browse

import app.shoalwatch.data.downloads.Downloads
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * The downloads on this phone that deleting [item] from the server takes with it, as [s] sees them: a film's or an episode's own,
 * or the episodes of a series or season (as Downloads lists them under it). Empty when there are none or they cannot be read.
 */
internal suspend fun downloadsUnder(downloads: Downloads?, s: Session, item: BulkItem): List<String> {
    val d = downloads ?: return emptyList()
    return try {
        when (item.kind) {
            ItemKind.SERIES -> d.series(s, item.id).first().seasons.flatMap { it.episodes }.map { it.download.itemId }
            ItemKind.SEASON -> {
                val seriesId = item.seriesId ?: return emptyList()
                d.series(s, seriesId).first().seasons.filter { it.season?.item?.id == item.id }.flatMap { it.episodes }.map { it.download.itemId }
            }
            else -> listOfNotNull(item.id.takeIf { d.status(s, item.id).first() != null })
        }.distinct()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * What this phone does once the server has deleted [itemId]. The delete has happened, so it counts as done whatever fails here:
 * each step is on its own and a failure costs only that step. With [keepCopy] the downloads in [saved] stay, and they and [itemId]
 * only leave this profile's lists (their records stay: a series' or season's record is what Downloads shows its episodes under).
 * Otherwise each download is removed and every record goes, as for an item the server refused.
 */
internal suspend fun tidyAfterServerDelete(
    downloads: Downloads?,
    s: Session,
    itemId: String,
    saved: List<String>,
    keepCopy: Boolean,
    forget: suspend (itemId: String, keepRecord: Boolean) -> Unit,
) {
    val keep = keepCopy && saved.isNotEmpty()
    for (download in saved) {
        if (!keep) step { downloads?.remove(s, download) }
        if (download != itemId) step { forget(download, keep) }
    }
    step { forget(itemId, keep) }
}

private inline fun step(block: () -> Unit) {
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Unit
    }
}
