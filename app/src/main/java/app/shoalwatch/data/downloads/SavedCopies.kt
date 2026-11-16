package app.shoalwatch.data.downloads

import app.shoalwatch.core.AppLog
import app.shoalwatch.data.db.downloads.DownloadDao
import app.shoalwatch.data.db.downloads.DownloadEntity
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.db.downloads.DownloadSegmentEntity
import app.shoalwatch.data.db.downloads.DownloadTrackEntity
import app.shoalwatch.data.db.downloads.OfflineLibraryDao
import app.shoalwatch.data.playback.MediaPaths
import app.shoalwatch.data.playback.SavedPlan
import app.shoalwatch.data.playback.SavedPlans
import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The rows a saved copy keeps besides its media (tracks and skip markers), for the player. */
internal interface SavedRows {
    suspend fun tracks(key: DownloadKey): List<DownloadTrackEntity>
    suspend fun segments(key: DownloadKey): List<DownloadSegmentEntity>
}

internal fun DownloadDao.savedRows(): SavedRows = object : SavedRows {
    override suspend fun tracks(key: DownloadKey) = this@savedRows.tracks(key.serverId, key.itemId)
    override suspend fun segments(key: DownloadKey) = this@savedRows.segments(key.serverId, key.itemId)
}

/**
 * THE lookup of a saved copy the player may open, for one session (the resolver's and `SavedFirstPlaybackGateway`'s). A copy is
 * offered only if all of these hold:
 *  - [OfflineLibraryDao.getPlayable] returns it: COMPLETE, the session has a `visible_items` record for it, and it owns it or
 *    sharing is on AND its own permission is confirmed (the strict sharing rule);
 *  - its storage is mounted now (`DownloadPaths.itemFolder` is not null) and every path comes from `DownloadPaths`;
 *  - a cheap check before every play: an original's media file is the recorded length; a conversion's playlist and every one of
 *    its recorded segments are there.
 *  - for playback ([plan]): a copy its own sign-in did not fetch (shared to it, inherited, or joined as "already on this
 *    phone": final review M-1) whose confirmation is over 7 days old is offered only once re-asked, or while the server cannot
 *    be reached ([staleWhileReachable]).
 * Anything else is null, so the item streams as before. Never throws (a failure is logged by class and is null).
 */
internal class SavedCopies(
    private val offline: OfflineLibraryDao,
    private val rows: SavedRows,
    private val paths: DownloadPaths,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val reachable: (Session) -> Boolean = { false },
    private val recheck: (Session) -> Unit = {},
) {
    suspend fun media(s: Session, itemId: String): SavedMedia? = found(s, itemId)?.second

    suspend fun plan(s: Session, itemId: String): SavedPlan? {
        val (row, media) = found(s, itemId) ?: return null
        val key = DownloadKey(row.serverId, row.itemId)
        if (staleWhileReachable(s, row.itemId)) return null
        return guarded {
            SavedPlan(media, SavedPlans.plan(row.itemId, media, rows.tracks(key), row.runtimeMs, rows.segments(key).isNotEmpty()))
        }
    }

    /**
     * The strict sharing rule's re-ask at the point of play (Tasks 13-16 review I-1): a copy this session's own sign-in did NOT
     * fetch (final review M-1) whose
     * confirmation is over 7 days old is not played while the server can be reached; a re-ask is asked for ([recheck], rate
     * limited by `SharingRecheck`) and the item streams meanwhile. Offline, a confirmation that cannot be re-asked still counts.
     * A failure to read it refuses the copy (it streams), never the other way round.
     */
    private suspend fun staleWhileReachable(s: Session, itemId: String): Boolean {
        val confirmedAt = try {
            offline.sharedConfirmedAt(s.serverId, s.userId, itemId) ?: return false // fetched by its own sign-in
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "saved copy not offered: ${e.javaClass.simpleName}")
            return true
        }
        if (ConfirmationAge.fresh(confirmedAt, now()) || !reachable(s)) return false
        recheck(s)
        return true
    }

    private suspend fun found(s: Session, itemId: String): Pair<DownloadEntity, SavedMedia>? {
        val id = JellyfinId.parseOrNull(itemId)?.value?.takeIf { it == itemId } ?: return null
        return guarded {
            val row = offline.getPlayable(s.serverId, s.userId, id) ?: return@guarded null
            withContext(io) { mediaOf(row) }?.let { row to it }
        }
    }

    private fun mediaOf(row: DownloadEntity): SavedMedia? {
        val source = row.mediaSourceId?.takeIf(MediaPaths::isSafeId) ?: return null
        val folder = paths.itemFolder(DownloadKey(row.serverId, row.itemId), row.storage, row.volumeId) ?: return null // the card is out
        val entry = QueueSpace.entryOf(paths, row, folder) ?: return null
        val hls = row.container == QueueSpace.HLS
        if (!(if (hls) segmentsThere(row, entry.parentFile) else lengthAsRecorded(row, entry))) return null
        return SavedMedia(row.serverId, row.itemId, folder, entry, hls, row.quality, source)
    }

    private fun lengthAsRecorded(row: DownloadEntity, media: File): Boolean {
        val expected = row.totalBytes ?: row.doneBytes.takeIf { it > 0 } ?: return false
        return media.isFile && media.length() == expected
    }

    private fun segmentsThere(row: DownloadEntity, hls: File?): Boolean {
        val count = row.segmentsTotal?.takeIf { it > 0 } ?: return false
        if (hls == null || !paths.localPlaylist(hls).isFile) return false
        return (0 until count).all { paths.segmentFile(hls, it).isFile }
    }

    private suspend fun <T> guarded(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) { // a database failure, or a path DownloadPaths refused
        AppLog.w(TAG, "saved copy not offered: ${e.javaClass.simpleName}")
        null
    }

    private companion object {
        const val TAG = "SavedCopies"
    }
}
