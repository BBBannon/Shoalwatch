package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.AppDatabase
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * [Playstate] on [LibraryGateway] and Room (see "Tidy-up actions" in docs/ARCHITECTURE.md). Every action is the same five steps:
 *
 *  1. snapshot what will change;
 *  2. change Room in ONE transaction under the library write gate, marking every `user_data` row `pendingSyncAt`;
 *  3. call the server, exactly once (the gateway's mutations already retry once; nothing here loops);
 *  4. on success, wait for any refresh that began before the server heard, clear the markers, then refresh what the server recomputes (not waited for);
 *  5. on failure, put the snapshot back exactly and throw the gateway's [AppError].
 *
 * **Milestone 5** replaces step 3 with a durable outbox: on failure it will KEEP the change and its `pendingSyncAt` marker
 * and queue the call, instead of rolling back. The marker is the hook; this class is the only thing that changes.
 *
 * The work runs in [scope] (the app scope) and the caller awaits it: a caller that is cancelled after step 2 stops waiting,
 * and the action still finishes, so no marker is ever left behind by a screen that went away. One action runs at a time per
 * (user, item); a second for the same item waits for the first. A season or series action also changes rows of items that
 * have their own key: an episode tapped at the very moment its season is marked can clear the marker early; that costs a
 * brief window in which a refresh may show the server's older answer for it, never a wrong final state.
 */
class PlaystateRepository(
    private val gateway: LibraryGateway,
    db: AppDatabase,
    private val library: LibraryRepository,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    clock: () -> Long,
) : Playstate {
    private val local = PlaystateLocal(db, clock)
    private val locks = KeyedLocks()

    override suspend fun setPlayed(s: Session, itemId: String, played: Boolean) =
        act(s, itemId, { item -> local.played(s, item, played) }, { id -> gateway.setPlayed(s, id, played) })

    override suspend fun setFavourite(s: Session, itemId: String, favourite: Boolean) =
        act(s, itemId, { item -> local.favourite(s, item.item.itemId, favourite) }, { id -> gateway.setFavourite(s, id, favourite) })

    override suspend fun removeFromContinueWatching(s: Session, itemId: String) =
        act(s, itemId, { item -> local.clearResume(s, item.item.itemId) }, { id -> gateway.clearResume(s, id) })

    /** [change] makes the optimistic change (steps 1 and 2) for the item as this user may see it; [send] is step 3. */
    private suspend fun act(
        s: Session,
        rawId: String,
        change: suspend (ItemRow) -> Touched,
        send: suspend (String) -> Unit,
    ) {
        val id = JellyfinId.parseOrNull(rawId)?.value ?: throw AppError.of(AppError.Kind.SERVER_NOT_FOUND)
        val done = CompletableDeferred<Unit>()
        scope.launch(io) {
            done.completeWith(runCatching { locks.withLock(Key(s, id)) { perform(s, id, change, send) } })
        }.invokeOnCompletion { if (!done.isCompleted) done.completeExceptionally(CancellationException("The app is shutting down.")) }
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
        send: suspend (String) -> Unit,
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
        try {
            send(id)
        } catch (e: Throwable) {
            withContext(NonCancellable) { rollBack(s, id, touched) }
            throw if (e is Exception && e !is CancellationException) AppError.from(e) else e // an Error or a cancellation goes on as it is
        }
        withContext(NonCancellable) {
            library.awaitRefreshes(s, touched.recompute)
            try {
                library.writeGated { local.transaction { local.clearPending(s, touched.rows.keys) } }
            } catch (e: Exception) {
                clearOneByOne(s, touched) // the database is unwell: do what can still be done so nothing stays pending
                throw e
            }
            library.refreshInBackground(s, touched.recompute)
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
        for (itemId in touched.rows.keys) {
            try {
                library.writeGated { local.transaction { local.clearPending(s, listOf(itemId)) } }
            } catch (_: Exception) {
            }
        }
    }

    private data class Key(val session: Session, val itemId: String)

    /** One mutex per key in use, dropped when nobody holds or waits for it. */
    private class KeyedLocks {
        private class Entry(val mutex: Mutex = Mutex(), var users: Int = 0)

        private val entries = HashMap<Key, Entry>()

        suspend fun <T> withLock(key: Key, block: suspend () -> T): T {
            val entry = synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
            try {
                return entry.mutex.withLock { block() }
            } finally {
                synchronized(entries) { if (--entry.users == 0) entries.remove(key) }
            }
        }
    }
}
