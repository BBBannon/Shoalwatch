package app.shoalwatch.data.images

import app.shoalwatch.core.AppLog
import coil3.memory.MemoryCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Clears a server's pictures when they should go: when the server is removed, and when an account is signed out and it was
 * the server's last (other accounts of the server on this phone may still be signed in and show the same pictures).
 * Runs off the main thread; a failure is logged by kind only and never thrown into the store's notification.
 */
class ArtworkEviction(
    private val caches: ArtworkDiskCaches,
    private val memory: () -> MemoryCache?,
    private val hasAccounts: suspend (serverId: String) -> Boolean,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    fun onServerRemoved(serverId: String) {
        scope.launch(io) { evict(serverId) }
    }

    fun onAccountGone(serverId: String) {
        scope.launch(io) {
            val none = try {
                !hasAccounts(serverId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "artwork eviction check failed", e)
                false
            }
            if (none) evict(serverId)
        }
    }

    /**
     * At start-up (security review M8 F8, Milestone 2 L2): deletes every picture folder whose server no longer exists. The
     * eviction on removal is fire-and-forget after the row has gone, so a process that dies then would leave the folder for good.
     * The folders are listed before the servers are read, so a server added meanwhile keeps its new folder. A failed read deletes nothing.
     */
    fun sweep(serverIds: suspend () -> Set<String>): Job = scope.launch(io) {
        try {
            val folders = caches.folderNames()
            val known = serverIds()
            folders.filterNot { it in known }.forEach { caches.deleteFolder(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "artwork sweep failed", e)
        }
    }

    /** Removes the server's memory entries (every key starting `art:<serverId>:`) and its disk cache. */
    suspend fun evict(serverId: String) {
        try {
            val prefix = "art:$serverId:"
            memory()?.let { cache -> cache.keys.filter { it.key.startsWith(prefix) }.forEach { cache.remove(it) } }
            withContext(io) { caches.evictServer(serverId) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "artwork eviction failed", e)
        }
    }

    private companion object {
        const val TAG = "Artwork"
    }
}
