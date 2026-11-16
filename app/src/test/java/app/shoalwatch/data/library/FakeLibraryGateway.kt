package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import app.shoalwatch.data.library.api.LibraryGateway
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [LibraryGateway] whose answers the test programs. Every call is recorded in [calls] (operation name, then the
 * arguments that matter, joined with `:`), can be held at [gate] until the test opens it, and can be made to fail.
 */
class FakeLibraryGateway : LibraryGateway {
    val calls = CopyOnWriteArrayList<String>()

    /** Every call waits here first, if set. Completing it lets the held calls go. */
    @Volatile var gate: CompletableDeferred<Unit>? = null

    /** Fails every call with this kind (null: none), or only the named operations. */
    @Volatile var failAll: AppError.Kind? = null
    val failOps = HashMap<String, AppError.Kind>()

    /** Throws this (not an AppError) from the named operation. */
    val throwOps = HashMap<String, Throwable>()

    /** Runs inside every call after the gate is open and before a programmed failure, so a test can act "while the server is answering". */
    @Volatile var onCall: suspend (String) -> Unit = {}

    /** Called once in every call after the gate and before [onCall] (so before a call is held): a test's clock can move on as a real server round trip would. */
    @Volatile var clockTick: () -> Unit = {}

    /** When true the programmed answer is worked out when the call ARRIVES, not when it is released: a held call then carries the state of the moment it was asked. */
    @Volatile var answerAtRequest = false

    private fun <T> answerNow(f: () -> T): Lazy<T> = if (answerAtRequest) lazyOf(f()) else lazy(f)

    /** Calls that were cancelled while waiting at the gate. */
    val cancelled = CopyOnWriteArrayList<String>()

    private val running = AtomicInteger()
    @Volatile var maxRunning = 0

    /** Most tidy-up mutations (setPlayed, setFavourite, clearResume) in the gateway at once. Refreshes do not count: they legitimately overlap a mutation. */
    @Volatile var maxMutationsRunning = 0
    private val mutationsRunning = AtomicInteger()

    var viewsFor: (Session) -> List<LibraryView> = { emptyList() }
    var resumeFor: (Session) -> List<ItemWithUserData> = { emptyList() }
    var nextUpFor: (Session) -> List<ItemWithUserData> = { emptyList() }
    var latestFor: (Session, String) -> List<ItemWithUserData> = { _, _ -> emptyList() }
    var childPage: (parentId: String, start: Int, limit: Int) -> Page<ItemWithUserData> = { _, start, _ -> Page(emptyList(), start, 0, start, true) }
    var itemFor: (String) -> ItemWithUserData = { error("no item programmed") }

    /** When set, answers a single-item fetch instead of [itemFor], knowing who asked. */
    var itemBySession: ((Session, String) -> ItemWithUserData)? = null
    var seasonsFor: (String) -> List<ItemWithUserData> = { emptyList() }
    var episodesFor: (seriesId: String, seasonId: String?) -> List<ItemWithUserData> = { _, _ -> emptyList() }
    var searchFor: (String) -> List<ItemWithUserData> = { emptyList() }

    fun count(prefix: String) = calls.count { it == prefix || it.startsWith("$prefix:") }

    private suspend fun enter(op: String, detail: String = ""): String {
        val name = if (detail.isEmpty()) op else "$op:$detail"
        calls += name
        val now = running.incrementAndGet()
        val mutation = op == "setPlayed" || op == "setFavourite" || op == "clearResume"
        val mutations = if (mutation) mutationsRunning.incrementAndGet() else 0
        synchronized(this) {
            maxRunning = maxOf(maxRunning, now)
            maxMutationsRunning = maxOf(maxMutationsRunning, mutations)
        }
        try {
            gate?.await()
            clockTick()
            onCall(name)
            throwOps[op]?.let { throw it }
            (failOps[op] ?: failAll)?.let { throw AppError.of(it) }
        } catch (e: CancellationException) {
            cancelled += name
            throw e
        } finally {
            running.decrementAndGet()
            if (mutation) mutationsRunning.decrementAndGet()
        }
        return name
    }

    override suspend fun views(s: Session): List<LibraryView> { enter("views"); return viewsFor(s) }

    override suspend fun children(
        s: Session, parentId: String, start: Int, limit: Int, sort: Sort, filter: ItemFilter, kinds: Set<ItemKind>, recursive: Boolean,
    ): Page<ItemWithUserData> {
        val answer = answerNow { childPage(parentId, start, limit) }
        enter("children", "$start:$limit:${ListShape(kinds, recursive).key}")
        return answer.value
    }

    override suspend fun item(s: Session, itemId: String): ItemWithUserData { val a = answerNow { itemBySession?.invoke(s, itemId) ?: itemFor(itemId) }; enter("item", itemId); return a.value }

    override suspend fun seasons(s: Session, seriesId: String): List<ItemWithUserData> { enter("seasons"); return seasonsFor(seriesId) }

    override suspend fun episodes(s: Session, seriesId: String, seasonId: String?): List<ItemWithUserData> {
        enter("episodes", seasonId ?: "all")
        return episodesFor(seriesId, seasonId)
    }

    override suspend fun resume(s: Session, limit: Int): List<ItemWithUserData> { val a = answerNow { resumeFor(s) }; enter("resume", "$limit"); return a.value }

    override suspend fun nextUp(s: Session, limit: Int): List<ItemWithUserData> { val a = answerNow { nextUpFor(s) }; enter("nextUp", "$limit"); return a.value }

    override suspend fun latest(s: Session, viewId: String, limit: Int): List<ItemWithUserData> { enter("latest", "$limit"); return latestFor(s, viewId) }

    override suspend fun search(s: Session, term: String, limit: Int): List<ItemWithUserData> { val a = answerNow { searchFor(term) }; enter("search", term); return a.value }

    override suspend fun setPlayed(s: Session, itemId: String, played: Boolean) { enter("setPlayed", "$itemId:$played") }

    override suspend fun setFavourite(s: Session, itemId: String, favourite: Boolean) { enter("setFavourite", "$itemId:$favourite") }

    override suspend fun clearResume(s: Session, itemId: String) { enter("clearResume", itemId) }

    /** A call that is not a [LibraryGateway] call (a sync send the tidy-up tests route here): logged, held and failed like one. */
    suspend fun other(op: String, detail: String) { enter(op, detail) }
}
