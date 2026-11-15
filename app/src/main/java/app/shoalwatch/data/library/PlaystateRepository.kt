package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.library.ItemRow
import app.shoalwatch.data.library.api.LibraryGateway
import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.data.servers.Session
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
 * [Playstate] on [LibraryGateway] and Room (see "Tidy-up actions" in docs/ARCHITECTURE.md). Every action is the same five steps:
 *
 *  1. snapshot what will change;
 *  2. change Room in ONE transaction under the library write gate, marking every `user_data` row `pendingSyncAt`;
 *  3. call the server, exactly once (the gateway's mutations already retry once; nothing here loops);
 *  4. on success, clear the markers, then refresh what the server recomputes (not waited for; a slow older answer cannot undo the change, see `LocalChangeLog`);
 *  5. on failure, put the snapshot back exactly and throw the gateway's [AppError].
 *
 * **Milestone 5** replaces step 3 with a durable outbox: on failure it will KEEP the change and its `pendingSyncAt` marker
 * and queue the call, instead of rolling back; and a marker with no live action will then mean "send this change", where
 * today the start-up sweep (`LibraryRepository`) discards it. The marker is the hook; this class is what changes.
 *
 * The work runs in [scope] (the app scope) and the caller awaits it: a caller that is cancelled after step 2 stops waiting,
 * and the action still finishes, so a screen that went away leaves no marker behind. (Two residual cases: the app scope itself
 * cancelled after the optimistic write, and a clear that fails; the queue clears leftovers when it next goes idle, the start-up sweep at the latest.)
 * **One action runs at a time per
 * user** (calls queue in the order they were made): sibling episodes share the season and series badges, so per-item
 * ordering would not be enough. Another user's actions do not wait.
 */
class PlaystateRepository(
    private val gateway: LibraryGateway,
    private val library: LibraryRepository,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    clock: () -> Long,
) : Playstate, LocalPlaystate {
    private val local = PlaystateLocal(library.store, clock)

    /** The end of each user's queue of actions: the next call waits for it. Swapped atomically, in the caller, so the order is the order of the calls. */
    private val tails = ConcurrentHashMap<Session, CompletableDeferred<Unit>>()

    override suspend fun setPlayed(s: Session, itemId: String, played: Boolean) =
        act(s, itemId, { item -> local.played(s, item, played) }, { id -> gateway.setPlayed(s, id, played) })

    override suspend fun setFavourite(s: Session, itemId: String, favourite: Boolean) =
        act(s, itemId, { item -> local.favourite(s, item.item.itemId, favourite) }, { id -> gateway.setFavourite(s, id, favourite) })

    override suspend fun removeFromContinueWatching(s: Session, itemId: String) =
        act(s, itemId, { item -> local.clearResume(s, item.item.itemId) }, { id -> gateway.clearResume(s, id) })

    /** Finished is marking watched; otherwise the position is a local change with nothing to send (the stop report carried it). */
    override suspend fun recordStop(s: Session, itemId: String, positionMs: Long, finished: Boolean) =
        if (finished) {
            setPlayed(s, itemId, true)
        } else {
            act(s, itemId, { item -> local.stoppedAt(s, item.item.itemId, positionMs.coerceAtLeast(0)) }, { })
        }

    /** Steps 1 and 2 only: the change keeps its marker for Milestone 5's outbox (an `offline_plays` row spares it from the sweeps). */
    override suspend fun recordLocalStop(s: Session, itemId: String, positionMs: Long, finished: Boolean) =
        act(s, itemId, { item -> if (finished) local.played(s, item, true) else local.stoppedAt(s, item.item.itemId, positionMs.coerceAtLeast(0)) }, null)

    /** [change] makes the optimistic change (steps 1 and 2) for the item as this user may see it; [send] is step 3 (null: none, the marker stays). */
    private suspend fun act(
        s: Session,
        rawId: String,
        change: suspend (ItemRow) -> Touched,
        send: (suspend (String) -> Unit)?,
    ) {
        val id = JellyfinId.parseOrNull(rawId)?.value ?: throw AppError.of(AppError.Kind.SERVER_NOT_FOUND)
        val done = CompletableDeferred<Unit>()
        val mine = CompletableDeferred<Unit>()
        val before = tails.put(s, mine) // synchronous: queue position is call order
        val work = scope.launch(io) {
            try {
                before?.await() // never fails: a queue entry is only ever completed normally
                done.completeWith(runCatching { perform(s, id, change, send) })
            } finally {
                // Last in the queue means the queue is about to go idle: nothing can be live, so any marker still set (a clear that
                // failed) is cleared now. Anything queued behind us waits for [mine], so it cannot have set a marker yet.
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

    private suspend fun perform(
        s: Session,
        id: String,
        change: suspend (ItemRow) -> Touched,
        send: (suspend (String) -> Unit)?,
    ) {
        val touched = try {
            library.writeGated {
                local.transaction {
                    val item = local.visibleItem(s, id) ?: throw AppError.of(AppError.Kind.SERVER_NOT_FOUND)
                    change(item)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw AppError.from(e) // a transaction that throws leaves nothing changed, so there is nothing to clear
        }
        if (send == null) return // a local stop: kept as it is, with its marker
        try {
            send(id)
        } catch (e: Throwable) {
            withContext(NonCancellable) { rollBack(s, id, touched) }
            throw if (e is Exception && e !is CancellationException) AppError.from(e) else e // an Error or a cancellation goes on as it is
        }
        withContext(NonCancellable) {
            try {
                library.writeGated { local.transaction { local.confirm(s, touched) } }
            } catch (_: Exception) {
                // The server accepted the change, so this is not the caller's failure. Clear what can still be cleared; a marker that
                // cannot be cleared is cleaned up by the start-up sweep.
                clearOneByOne(s, touched)
            }
            library.refreshInBackground(s, touched.recompute)
        }
    }

    private suspend fun clearLeftovers(s: Session) {
        try {
            library.writeGated { local.transaction { local.clearLeftovers(s) } }
        } catch (_: Exception) {
            // Still stuck: the next idle moment, or the next start, tries again.
        }
    }

    /**
     * Exact restore under the write gate. If even that fails (the database is unwell), every marker is cleared one row at a
     * time so nothing is stuck pending, and the lists are refreshed so the cache reconverges with the server.
     */
    private suspend fun rollBack(s: Session, id: String, touched: Touched) {
        try {
            library.writeGated { local.transaction { local.restore(s, touched) } }
        } catch (_: Exception) {
            clearOneByOne(s, touched)
            library.refreshInBackground(s, touched.recompute + Recompute.Item(id))
        }
    }

    /** Best effort: each row on its own, so one that cannot be written does not keep the others pending. */
    private suspend fun clearOneByOne(s: Session, touched: Touched) {
        for (itemId in touched.markedIds) {
            try {
                library.writeGated { local.transaction { local.clearPending(s, listOf(itemId)) } }
            } catch (_: Exception) {
            }
        }
    }
}
