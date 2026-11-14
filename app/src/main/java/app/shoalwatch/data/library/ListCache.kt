package app.shoalwatch.data.library

import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.db.library.LibraryStore
import app.shoalwatch.data.db.library.ListStateEntity
import app.shoalwatch.data.db.library.toModel
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.CoroutineContext

/** How to bring one list up to date: its key, how long a copy stays current, and the work that fetches and saves it. */
internal class Recipe(val key: String, val window: Duration, val run: suspend () -> Unit)

/** The saved rows of a list, its saved state (null: never loaded) and how current it is. */
internal data class Cached(val rows: List<ItemWithUserData>, val state: ListStateEntity?, val freshness: Freshness)

/**
 * The cache-first machinery every screen shares: read what Room holds, and start a refresh in the background when the
 * list was never loaded or is older than its window. Room is the only thing readers observe; the network only ever
 * writes to it, through here.
 *
 * Every write takes [writeGate], so list writes and eviction never overlap each other, and one list's writes never
 * interleave (a list also has at most one refresh in flight, see [Refresher]).
 */
internal class ListCache(
    private val db: AppDatabase,
    private val store: LibraryStore,
    private val refresher: Refresher,
    private val maintenance: LibraryMaintenance,
    private val clock: () -> Long,
    private val io: CoroutineContext,
    private val writeGate: Mutex,
) {
    private val lists = db.listDao()

    private fun now(): Instant = Instant.ofEpochMilli(clock())

    // ---- reading

    /** Starts a refresh unless the list is loaded and still within its window. Does not wait for it. */
    suspend fun ensure(s: Session, recipe: Recipe) {
        maintenance.kick()
        val state = lists.getState(s.serverId, s.userId, recipe.key)
        if (state == null || clock() - state.refreshedAt >= recipe.window.toMillis()) {
            refresher.launch(ListId(s, recipe.key), Refresher.Mode.REFRESH, recipe.run)
        }
    }

    /** The saved rows at once (even none), then every change; refreshes in the background when due. */
    fun rows(s: Session, recipe: Recipe, limit: Int): Flow<Cached> = flow {
        ensure(s, recipe)
        emitAll(
            combine(
                lists.observeList(s.serverId, s.userId, recipe.key, limit),
                lists.observeState(s.serverId, s.userId, recipe.key),
                refresher.attempt(ListId(s, recipe.key)),
            ) { rows, state, attempt ->
                // Room reports the list and its state separately: a state that says "loaded" beside no rows is the write still arriving.
                val loaded = state != null && !(rows.isEmpty() && state.loadedCount > 0)
                Cached(rows.map { it.toModel() }, state, freshnessOf(attempt, loaded))
            },
        )
    }.distinctUntilChanged().flowOn(io)

    /** Only how current a state-only key (a library list, an item) is; same trigger as [rows]. */
    fun status(s: Session, recipe: Recipe): Flow<Freshness> = flow {
        ensure(s, recipe)
        emitAll(
            combine(lists.observeState(s.serverId, s.userId, recipe.key), refresher.attempt(ListId(s, recipe.key))) { state, attempt ->
                freshnessOf(attempt, state != null)
            },
        )
    }.distinctUntilChanged().flowOn(io)

    /** Idle with no saved state is only seen before a refresh has started (or after the app scope was cancelled): shown as refreshing. */
    private fun freshnessOf(attempt: Attempt, loaded: Boolean): Freshness = when (attempt) {
        Attempt.Running -> Freshness.Refreshing
        is Attempt.Failed -> Freshness.Stale(attempt.kind)
        Attempt.Idle -> if (loaded) Freshness.Fresh else Freshness.Refreshing
    }

    /** Joins the refresh already running for this list, or starts one, and returns when it has finished (it never throws). */
    suspend fun refreshNow(s: Session, recipe: Recipe) {
        refresher.launch(ListId(s, recipe.key), Refresher.Mode.REFRESH, recipe.run)?.join()
    }

    // ---- writing (all under the gate)

    /** Saves one page of a list, replacing from [start] on (0 replaces the list). See `LibraryStore.writePage`. */
    suspend fun writePage(
        s: Session,
        key: String,
        start: Int,
        rows: List<ItemWithUserData>,
        total: Int?,
        nextStartIndex: Int,
        pagesLoaded: Int,
        endReached: Boolean,
    ) {
        val reconciled = reconcileUserData(s, rows)
        writeGate.withLock { store.writePage(s, key, start, reconciled, total, now(), nextStartIndex, pagesLoaded, endReached) }
    }

    /** A list that is read in one go (resume, next up, latest, seasons, episodes). */
    suspend fun writeWhole(s: Session, key: String, rows: List<ItemWithUserData>) =
        writePage(s, key, 0, rows, rows.size, rows.size, 1, true)

    /** Saves items without a list (a detail fetch, search results). */
    suspend fun writeItems(s: Session, rows: List<ItemWithUserData>) {
        val reconciled = reconcileUserData(s, rows)
        writeGate.withLock { store.writeItems(s, reconciled, now()) }
    }

    suspend fun replaceViews(s: Session, views: List<LibraryView>) = writeGate.withLock { store.replaceViews(s, views) }

    /** Records "loaded now" for a key that holds no entries (views, an item): a state row with nothing in it. */
    suspend fun markLoaded(s: Session, key: String) = writePage(s, key, 0, emptyList(), 0, 0, 1, true)

    /**
     * THE one place that decides whose copy of a user's watched state wins when the server's answer is saved. Today the
     * server's wins: what it sent overwrites the cached copy. Milestone 5's playstate outbox changes only this function: a
     * row with a change still waiting to be sent will keep the local value here, so a refresh cannot undo it.
     */
    @Suppress("RedundantSuspendModifier", "UNUSED_PARAMETER")
    private suspend fun reconcileUserData(s: Session, rows: List<ItemWithUserData>): List<ItemWithUserData> = rows
}
