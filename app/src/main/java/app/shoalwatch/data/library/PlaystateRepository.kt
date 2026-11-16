package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.library.ItemRow
import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.sync.Change
import app.shoalwatch.data.sync.ChangeKind
import app.shoalwatch.data.sync.OutboxStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * [Playstate] on Room and the durable outbox (see "Tidy-up actions" in docs/ARCHITECTURE.md). Every action is:
 *
 *  1. the item must be one this user was shown (else `SERVER_NOT_FOUND`, nothing changed);
 *  2. under the library write gate, in ONE transaction: the local change (every row marked `pendingSyncAt`, noted in
 *     `LocalChangeLog`) AND its outbox row ([OutboxStore.enqueue]) with the rows it marked, the lists it took items out of and
 *     the lists the server recomputes;
 *  3. [changed] (`SyncScheduler.changed`: send now, and the background job), then return. No server call, no rollback: the
 *     sender sends the row in order, and `PlaystateLanding` clears the markers and refreshes the lists once the server has it.
 *
 * The work runs in [scope] (the app scope) and the caller awaits it: a caller that is cancelled after step 2 stops waiting, and
 * the action still finishes. **One action runs at a time per user** (calls queue in the order they were made), but each holds
 * the queue only for its local transaction, so the next tap shows at once. When a user's queue goes idle, a marker no outbox
 * row covers (a leftover of a process killed before this milestone, or a stop the server already had) is cleared.
 */
class PlaystateRepository internal constructor(
    private val library: LibraryRepository,
    private val outbox: OutboxStore,
    private val changed: (Session) -> Unit,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
) : Playstate, LocalPlaystate {
    private val local = PlaystateLocal(library.store, clock)

    /** The end of each user's queue of actions: the next call waits for it. Swapped atomically, in the caller, so the order is the order of the calls. */
    private val tails = ConcurrentHashMap<Session, CompletableDeferred<Unit>>()

    override suspend fun setPlayed(s: Session, itemId: String, played: Boolean) =
        act(s, itemId) { item -> local.played(s, item, played) to change(item, ChangeKind.PLAYED, flag = played) }

    override suspend fun setFavourite(s: Session, itemId: String, favourite: Boolean) =
        act(s, itemId) { item -> local.favourite(s, item.item.itemId, favourite) to change(item, ChangeKind.FAVOURITE, flag = favourite) }

    override suspend fun removeFromContinueWatching(s: Session, itemId: String) =
        act(s, itemId) { item -> local.clearResume(s, item.item.itemId) to change(item, ChangeKind.POSITION, positionMs = 0) }

    override suspend fun recordStop(s: Session, itemId: String, positionMs: Long, finished: Boolean, reached: Boolean) =
        act(s, itemId) { item ->
            val p = positionMs.coerceAtLeast(0)
            when {
                // Always queued (review I1): "Finished" can fire at the Outro, below the server's own 90% "played" mark. Absolute, so never counted twice.
                finished -> local.played(s, item, true) to change(item, ChangeKind.PLAYED, flag = true)
                else -> local.stoppedAt(s, item.item.itemId, p) to if (reached) null else change(item, ChangeKind.POSITION, positionMs = p)
            }
        }

    /** A LOCAL play (a saved copy): a finished one is one PLAY at now (the play count goes up by one, once); else the position. */
    override suspend fun recordLocalStop(s: Session, itemId: String, positionMs: Long, finished: Boolean) =
        act(s, itemId) { item ->
            val p = positionMs.coerceAtLeast(0)
            if (finished) {
                local.played(s, item, true) to change(item, ChangeKind.PLAY, fromOfflinePlay = true)
            } else {
                local.stoppedAt(s, item.item.itemId, p) to change(item, ChangeKind.POSITION, positionMs = p, fromOfflinePlay = true)
            }
        }

    private fun change(item: ItemRow, kind: ChangeKind, flag: Boolean? = null, positionMs: Long? = null, fromOfflinePlay: Boolean = false) =
        Change(item.item.itemId, item.item.kind, kind, flag = flag, positionMs = positionMs, occurredAt = clock(), fromOfflinePlay = fromOfflinePlay)

    /** [change] makes the local change for the item as this user may see it, and says what to queue (null: nothing, the server has it). */
    private suspend fun act(s: Session, rawId: String, change: suspend (ItemRow) -> Pair<Touched, Change?>) {
        val id = JellyfinId.parseOrNull(rawId)?.value ?: throw AppError.of(AppError.Kind.SERVER_NOT_FOUND)
        val done = CompletableDeferred<Unit>()
        val mine = CompletableDeferred<Unit>()
        val before = tails.put(s, mine) // synchronous: queue position is call order
        val work = scope.launch(io) {
            try {
                before?.await() // never fails: a queue entry is only ever completed normally
                done.completeWith(runCatching { perform(s, id, change) })
            } finally {
                // Last in the queue means the queue is about to go idle: a marker no outbox row covers cannot be live, so it is cleared now.
                try {
                    if (tails[s] === mine) withContext(NonCancellable) { clearLeftovers(s) }
                } finally {
                    mine.complete(Unit)
                    tails.remove(s, mine)
                }
            }
        }
        work.invokeOnCompletion {
            mine.complete(Unit)
            if (!done.isCompleted) done.completeExceptionally(CancellationException("The app is shutting down."))
        }
        try {
            done.await() // cancelling the caller stops this wait, not the work above
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AppError.from(e) // the seam only ever throws AppError (an AppError passes through unchanged)
        }
    }

    private suspend fun perform(s: Session, id: String, change: suspend (ItemRow) -> Pair<Touched, Change?>) {
        val (touched, queued) = try {
            library.writeGated {
                local.transaction {
                    val item = local.visibleItem(s, id) ?: throw AppError.of(AppError.Kind.SERVER_NOT_FOUND)
                    val (touched, queued) = change(item)
                    if (queued == null) {
                        local.clearPending(s, touched.markedIds.filterNot { outbox.hasMark(s, it) }) // the server has it; an unsent row keeps its own
                    } else {
                        val marks = OutboxStore.Marks(touched.markedIds, touched.removed.map { it.itemId to it.listKey })
                        outbox.enqueue(s, queued, marks, touched.recompute.map { it.key() })
                    }
                    touched to queued
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AppError.from(e) // a transaction that throws leaves nothing changed, and nothing queued
        }
        if (queued == null) library.refreshInBackground(s, touched.recompute) else changed(s)
    }

    private suspend fun clearLeftovers(s: Session) {
        try {
            library.writeGated { local.transaction { local.clearLeftovers(s) } }
        } catch (_: Exception) {
            // Still stuck: the next idle moment, or the next start, tries again.
        }
    }
}
