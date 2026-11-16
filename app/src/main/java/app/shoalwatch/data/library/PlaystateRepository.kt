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

    override suspend fun recordStop(s: Session, itemId: String, positionMs: Long, finished: Boolean, reached: Boolean, kick: Boolean, stoppedAt: Long?) =
        act(s, itemId, kick) { item ->
            val p = positionMs.coerceAtLeast(0)
            when {
                // Always queued (review I1): "Finished" can fire at the Outro, below the server's own 90% "played" mark. Absolute, so never counted twice.
                finished -> local.played(s, item, true) to change(item, ChangeKind.PLAYED, flag = true)
                // A stop the server heard carries the newest position as of the stop (review M3): unsent positions up to the stop's
                // own record go. One the user made after it (a removal while the report was in flight) stays, and so does its local
                // value: the stop's first record already saved this position (Tasks 9-10 review M1).
                reached -> {
                    val id = item.item.itemId
                    // Up to this stop's own time (Tasks 11-12 review M5), so an overlapping later stop keeps its row.
                    val newer = outbox.dropSameUntil(s, id, ChangeKind.POSITION, stoppedAt ?: clock())
                    (if (newer) local.heard(s, id) else local.stoppedAt(s, id, p)) to null
                }
                else -> local.stoppedAt(s, item.item.itemId, p) to change(item, ChangeKind.POSITION, positionMs = p, at = stoppedAt)
            }
        }

    /**
     * "Watched — delete download" pressed while the saved copy plays (Tasks 11-12 review I1): watched on this phone only, nothing
     * queued, because the coming LOCAL stop's PLAY carries the watch to the server (a PLAYED tick as well would count it twice).
     * Its pending marker is kept (no refresh is asked for), so the server's older copy does not undo the tick before the stop.
     */
    override suspend fun markWatchedHere(s: Session, itemId: String) =
        act(s, itemId, kick = false, keepPending = true) { item -> local.played(s, item, true) to null }

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

    private fun change(item: ItemRow, kind: ChangeKind, flag: Boolean? = null, positionMs: Long? = null, fromOfflinePlay: Boolean = false, at: Long? = null) =
        Change(item.item.itemId, item.item.kind, kind, flag = flag, positionMs = positionMs, occurredAt = at ?: clock(), fromOfflinePlay = fromOfflinePlay)

    /** [change] makes the local change for the item as this user may see it, and says what to queue (null: nothing, the server has it). */
    /** [kick] false: the row is queued but no send is asked for (the caller asks once its own report has gone). */
    private suspend fun act(s: Session, rawId: String, kick: Boolean = true, keepPending: Boolean = false, change: suspend (ItemRow) -> Pair<Touched, Change?>) {
        val id = JellyfinId.parseOrNull(rawId)?.value ?: throw AppError.of(AppError.Kind.SERVER_NOT_FOUND)
        val done = CompletableDeferred<Unit>()
        val mine = CompletableDeferred<Unit>()
        val before = tails.put(s, mine) // synchronous: queue position is call order
        val work = scope.launch(io) {
            try {
                before?.await() // never fails: a queue entry is only ever completed normally
                done.completeWith(runCatching { perform(s, id, kick, keepPending, change) })
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

    private suspend fun perform(s: Session, id: String, kick: Boolean, keepPending: Boolean, change: suspend (ItemRow) -> Pair<Touched, Change?>) {
        val (touched, queued) = try {
            library.writeGated {
                local.transaction {
                    val item = local.visibleItem(s, id) ?: throw AppError.of(AppError.Kind.SERVER_NOT_FOUND)
                    val (touched, queued) = change(item)
                    if (queued == null) {
                        if (!keepPending) local.clearPending(s, touched.markedIds.filterNot { outbox.hasMark(s, it) }) // the server has it; an unsent row keeps its own
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
        if (queued == null) { if (!keepPending) library.refreshInBackground(s, touched.recompute) } else if (kick) changed(s)
    }

    private suspend fun clearLeftovers(s: Session) {
        try {
            library.writeGated { local.transaction { local.clearLeftovers(s) } }
        } catch (_: Exception) {
            // Still stuck: the next idle moment, or the next start, tries again.
        }
    }
}
