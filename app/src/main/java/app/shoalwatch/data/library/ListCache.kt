package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.db.library.LibraryStore
import app.shoalwatch.data.db.library.PageResult
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
 * Whose copy of a user's watched state wins is decided in one place, `ItemDao.writeServerUserData` (reached through
 * `LibraryStore`): a row with a local change the server has not confirmed (`pendingSyncAt`) is never overwritten by a
 * refresh. Milestone 5's playstate outbox sets and clears that marker; nothing here needs to know.
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
    /** Runs before a list is judged fresh or stale: the once-per-process start-up sweep, which ages the lists of users it cleared markers for. */
    private val beforeUse: suspend () -> Unit = {},
) {
    private val lists = db.listDao()

    private fun now(): Instant = Instant.ofEpochMilli(clock())

    // ---- reading

    /** Starts a refresh unless the list is loaded and still within its window. Does not wait for it. */
    suspend fun ensure(s: Session, recipe: Recipe) {
        beforeUse()
        maintenance.kick()
        val state = lists.getState(s.serverId, s.userId, recipe.key)
        if (state == null || clock() - state.refreshedAt >= recipe.window.toMillis()) {
            val id = ListId(s, recipe.key)
            // A list that failed a moment ago is not asked for again by every new collector (a screen that keeps being
            // reopened must not become a request storm); pull-to-refresh ([refreshNow]) always tries.
            val failedLately = refresher.lastFailure(id)?.let { clock() - it.at in 0 until RETRY_COOLDOWN_MS } == true
            if (!failedLately) refresher.launch(id, Refresher.Mode.REFRESH, recipe.run)
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
                val arriving = state != null && rows.isEmpty() && state.loadedCount > 0
                Cached(rows.map { it.toModel() }, state, freshnessOf(attempt, state != null, arriving))
            },
        )
    }.distinctUntilChanged().flowOn(io)

    /** Only how current a state-only key (a library list, an item) is; same trigger as [rows]. */
    fun status(s: Session, recipe: Recipe): Flow<Freshness> = flow {
        ensure(s, recipe)
        emitAll(
            combine(lists.observeState(s.serverId, s.userId, recipe.key), refresher.attempt(ListId(s, recipe.key))) { state, attempt ->
                freshnessOf(attempt, state != null, arriving = false)
            },
        )
    }.distinctUntilChanged().flowOn(io)

    /**
     * Idle with nothing saved means nothing is running and nothing was ever loaded (the work vanished, e.g. the app scope was
     * cancelled): that is `Stale(UNKNOWN)`, never a spinner that no one will stop. The cold start still reads `Refreshing`
     * because [ensure] has already started the first refresh. [arriving] is a write that has committed but whose rows Room
     * has not yet reported.
     */
    private fun freshnessOf(attempt: Attempt, loaded: Boolean, arriving: Boolean): Freshness = when (attempt) {
        Attempt.Running -> Freshness.Refreshing
        is Attempt.Failed -> Freshness.Stale(attempt.kind)
        Attempt.Done -> if (loaded && !arriving) Freshness.Fresh else Freshness.Refreshing // a write that has committed but that Room has not reported yet
        Attempt.Idle -> if (arriving) Freshness.Refreshing else if (loaded) Freshness.Fresh else Freshness.Stale(AppError.Kind.UNKNOWN)
    }

    private companion object {
        /** After a failed refresh, collecting the list again does not retry for this long. */
        const val RETRY_COOLDOWN_MS = 30_000L
    }

    /** Joins the refresh already running for this list, or starts one, and returns when it has finished (it never throws). */
    suspend fun refreshNow(s: Session, recipe: Recipe) {
        refresher.launch(ListId(s, recipe.key), Refresher.Mode.REFRESH, recipe.run)?.join()
    }

    // ---- writing (all under the gate)

    /** Saves one page of a list, replacing from [start] on (0 replaces the list). See `LibraryStore.writePage`: a [PageResult.Stale] page (the list changed while it was being fetched) is not written and the caller simply drops it. */
    suspend fun writePage(
        s: Session,
        key: String,
        start: Int,
        rows: List<ItemWithUserData>,
        total: Int?,
        nextStartIndex: Int,
        pagesLoaded: Int,
        endReached: Boolean,
        basedOn: ListStateEntity? = null,
        requestedAtSeq: Long,
    ): PageResult {
        return writeGate.withLock { store.writePage(s, key, start, rows, total, now(), nextStartIndex, pagesLoaded, endReached, basedOn, requestedAtSeq) }
    }

    /** A list that is read in one go (resume, next up, latest, seasons, episodes). */
    suspend fun writeWhole(s: Session, key: String, rows: List<ItemWithUserData>, requestedAtSeq: Long) =
        writePage(s, key, 0, rows, rows.size, rows.size, 1, true, requestedAtSeq = requestedAtSeq)

    /** Saves items without a list (a detail fetch, search results). */
    suspend fun writeItems(s: Session, rows: List<ItemWithUserData>, authoritative: Boolean = false, requestedAtSeq: Long) {
        writeGate.withLock { store.writeItems(s, rows, now(), authoritative, requestedAtSeq) }
    }

    /** The server refused [itemId] for this user: it disappears for them (see `LibraryStore.revokeVisibility`). */
    suspend fun revoke(s: Session, itemId: String) = writeGate.withLock { store.revokeVisibility(s, itemId) }

    /** Milestone 10: [itemId] leaves every list of this user; its record stays (see `LibraryStore.leaveLists`). */
    suspend fun leaveLists(s: Session, itemId: String) = writeGate.withLock { store.leaveLists(s, itemId) }

    suspend fun replaceViews(s: Session, views: List<LibraryView>) = writeGate.withLock { store.replaceViews(s, views) }

    /** Records "loaded now" for a key that holds no entries (views, an item): a state row with nothing in it. */
    suspend fun markLoaded(s: Session, key: String) = writePage(s, key, 0, emptyList(), 0, 0, 1, true, requestedAtSeq = store.requestStamp())
}
