package app.shoalwatch.data.downloads

import app.shoalwatch.core.AppError
import app.shoalwatch.core.AppLog
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.db.downloads.DownloadStore
import app.shoalwatch.data.downloads.api.DownloadGateway
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
    private val gateway: DownloadGateway,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Whether the server (by id) can be reached now: while it can, only a fresh confirmation inherits (final review M-1). */
    private val reachable: (String) -> Boolean = { false },
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
            // While the server can be reached, only a confirmation younger than a week inherits (final review M-1).
            val online = reachable(serverId)
            val since = if (online) now() - ConfirmationAge.STALE_AFTER_MS else Long.MIN_VALUE
            val until = if (online) now() + ConfirmationAge.SKEW_MS else Long.MAX_VALUE
            for (key in store.afterAccountGone(serverId, since, until)) { queue.forget(key); DownloadRepository.deleteFiles(paths, key) }
            unpinnedArtwork()
        }
    }

    /**
     * Run by `SharingRecheck` (session change, server back, app to the front, a stale copy about to play; rate limited): re-asks
     * the server about up to 50 items (and their series) this profile holds under the strict sharing rule (shared to it, or owned
     * without its own sign-in having fetched them) and has not had confirmed for 7 days, or whose permission it never confirmed.
     * A 403 or 404 revokes that profile's record (the library's rule), so the item is hidden from it at once; the owner still
     * sees it. Then ONE `facts` call records this profile's own CanDownload: true is a confirmed permission (the strict sharing
     * rule), anything else removes it, and an explicit false also ends an ownership this sign-in never fetched (its files go if
     * it was the last owner: final review M-1). A refused item (the library revokes it and throws `SERVER_NOT_FOUND`, Tasks 11-12
     * review I-2) or one whose refresh fails otherwise (a 500, say: final review L-1) does not stop the rest or the permission
     * check. Only offline, a sign-in that no longer works, or a transport or identity failure ([STOPS]: final review re-review
     * L-d) stops it, changing nothing more: it is asked again next time.
     * [first]: the item about to play (a stale copy refused at play), asked about before the oldest (final review L-11).
     * Returns false when nothing was stale (no request was made), so `SharingRecheck` does not count the run (walkthrough W-3).
     */
    suspend fun reconfirm(s: Session, first: String? = null): Boolean = withContext(io) {
        val now = now()
        val stale = store.unconfirmedShared(s, now - ConfirmationAge.STALE_AFTER_MS, now + ConfirmationAge.SKEW_MS, LIMIT, first)
        if (stale.isEmpty()) return@withContext false
        val ids =(stale.map { it.first } + stale.mapNotNull { it.second }).distinct().take(LIMIT)
        try {
            for (id in ids) {
                try {
                    library.refresh(s, RefreshTarget.Item(id))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Refused (already revoked), or this one item failed: the others are still asked (final review L-1).
                    if (AppError.from(e).kind in STOPS) throw e
                }
            }
            val asked = stale.map { it.first }
            if (asked.isNotEmpty()) {
                val answers = gateway.facts(s, asked).associateBy { it.itemId }
                for (id in asked) {
                    val key = DownloadKey(s.serverId, id)
                    when (answers[id]?.canDownload) {
                        true -> store.confirm(key, s.userId)
                        false -> store.refused(s, id)?.let { gone -> queue.forget(gone); DownloadRepository.deleteFiles(paths, gone); unpinnedArtwork() }
                        null -> store.unconfirm(key, s.userId)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "downloads reconfirm stopped", e)
        }
        true
    }

    /** Pictures whose item no download pins any more: row and file. */
    private suspend fun unpinnedArtwork() = DownloadRepository.dropUnpinnedArtwork(store, paths)

    private suspend fun safely(what: String, block: suspend () -> Unit) = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.w(TAG, "downloads $what failed", e)
    }

    private companion object {
        const val TAG = "DownloadHousekeeping"
        /**
         * The failures that end a re-ask: nothing more can be asked of the server with this sign-in now (offline, signed out, or a
         * transport or identity failure: a server that hangs ends the pass at once rather than one item at a time, final review
         * re-review L-d). A server error, a refusal or anything unknown skips that one item.
         */
        val STOPS = setOf(
            AppError.Kind.OFFLINE, AppError.Kind.UNAUTHORISED, AppError.Kind.NOT_SIGNED_IN,
            AppError.Kind.NETWORK, AppError.Kind.TIMEOUT, AppError.Kind.TLS_UNTRUSTED, AppError.Kind.TLS_PIN_CHANGED,
            AppError.Kind.CLEARTEXT_BLOCKED, AppError.Kind.CLIENT_CERT_REQUIRED, AppError.Kind.SERVER_MISMATCH,
            AppError.Kind.SERVER_UNSUPPORTED, AppError.Kind.SECURE_CONNECTION_LOST, AppError.Kind.SECURE_CONNECTION_LOST_QUICK_CONNECT,
            AppError.Kind.CONNECTION_CHANGED,
        )
        const val LIMIT = 50
    }
}
