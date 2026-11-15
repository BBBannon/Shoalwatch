package app.shoalwatch.data.downloads

import app.shoalwatch.core.AppLog
import app.shoalwatch.data.db.downloads.DownloadStore
import app.shoalwatch.data.library.Library
import app.shoalwatch.data.library.RefreshTarget
import app.shoalwatch.data.servers.Session
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The removal rules and the start-up tidy-up (see "Removal rules" in docs/ARCHITECTURE.md), all off the main thread in [scope]:
 * - **server removed:** its rows have cascaded; its folder goes on every mounted root (a card that is out is swept by the next
 *   [onStart]); a running item of it finds its row gone at its next write and stops.
 * - **account gone:** the ownership rule ([DownloadStore.afterAccountGone]), then the files of what it deleted. A 401 keeps the
 *   account row, so its owners stay and nothing is deleted.
 * - **start:** interrupted rows back in the queue (only while nothing drains), the orphan sweep and pictures nobody pins
 *   (only while no download can start), then a kick if anything waits.
 */
internal class DownloadHousekeeping(
    private val store: DownloadStore,
    private val paths: DownloadPaths,
    private val queue: DownloadQueue,
    private val scheduler: DownloadScheduler,
    private val library: Library,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val started = AtomicBoolean(false)

    /** Once per process. */
    fun onStart(): Job? {
        if (!started.compareAndSet(false, true)) return null
        return scope.launch(io) {
            safely("start") {
                queue.requeueIfIdle()
                queue.whileNoneStarts {
                    store.sweepInputs()?.let { inputs ->
                        paths.sweep(inputs.known, inputs.servers, inputs.artwork)
                        unpinnedArtwork()
                    }
                }
                if (store.waitingCount() > 0) scheduler.kick()
            }
        }
    }

    fun onServerRemoved(serverId: String): Job = scope.launch(io) {
        safely("server removed") { for (folder in paths.serverFolders(serverId)) paths.deleteInside(folder) }
    }

    fun onAccountGone(serverId: String, userId: String): Job = scope.launch(io) {
        safely("account gone") {
            for (key in store.afterAccountGone(serverId)) DownloadRepository.deleteFiles(paths, key)
            unpinnedArtwork()
        }
    }

    /**
     * When a profile opens Downloads online: re-asks the server about up to 50 shared items (and their series) this profile
     * has not had confirmed for 7 days. A 403 or 404 revokes that profile's record (the library's rule), so the item is hidden
     * from it at once; the owner still sees it. Stops at the first failure (offline, say): it is asked again next time.
     */
    suspend fun reconfirm(s: Session) = withContext(io) {
        val stale = store.unconfirmedShared(s, now() - WEEK_MS, LIMIT)
        val ids = (stale.map { it.first } + stale.mapNotNull { it.second }).distinct().take(LIMIT)
        try {
            for (id in ids) library.refresh(s, RefreshTarget.Item(id))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "downloads reconfirm stopped", e)
        }
    }

    /** Pictures whose item no download pins any more: row and file. */
    private suspend fun unpinnedArtwork() {
        for (art in store.unpinnedArtwork()) {
            store.unpinArtwork(art)
            runCatching { paths.deleteInside(paths.artworkFile(art.serverId, art.itemId, art.slot, art.tag)) }
        }
    }

    private suspend fun safely(what: String, block: suspend () -> Unit) = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.w(TAG, "downloads $what failed", e)
    }

    private companion object {
        const val TAG = "DownloadHousekeeping"
        const val WEEK_MS = 7L * 24 * 60 * 60 * 1_000
        const val LIMIT = 50
    }
}
