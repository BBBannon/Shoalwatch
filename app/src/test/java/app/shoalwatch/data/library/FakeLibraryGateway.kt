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

    /** Calls that were cancelled while waiting at the gate. */
    val cancelled = CopyOnWriteArrayList<String>()

    private val running = AtomicInteger()
    @Volatile var maxRunning = 0

    var viewsFor: (Session) -> List<LibraryView> = { emptyList() }
    var resumeFor: (Session) -> List<ItemWithUserData> = { emptyList() }
    var nextUpFor: (Session) -> List<ItemWithUserData> = { emptyList() }
    var latestFor: (Session, String) -> List<ItemWithUserData> = { _, _ -> emptyList() }
    var childPage: (parentId: String, start: Int, limit: Int) -> Page<ItemWithUserData> = { _, start, _ -> Page(emptyList(), start, 0, start, true) }
    var itemFor: (String) -> ItemWithUserData = { error("no item programmed") }
    var seasonsFor: (String) -> List<ItemWithUserData> = { emptyList() }
    var episodesFor: (seriesId: String, seasonId: String?) -> List<ItemWithUserData> = { _, _ -> emptyList() }
    var searchFor: (String) -> List<ItemWithUserData> = { emptyList() }

    fun count(prefix: String) = calls.count { it == prefix || it.startsWith("$prefix:") }

    private suspend fun enter(op: String, detail: String = ""): String {
        val name = if (detail.isEmpty()) op else "$op:$detail"
        calls += name
        val now = running.incrementAndGet()
        synchronized(this) { maxRunning = maxOf(maxRunning, now) }
        try {
            gate?.await()
            onCall(name)
            throwOps[op]?.let { throw it }
            (failOps[op] ?: failAll)?.let { throw AppError.of(it) }
        } catch (e: CancellationException) {
            cancelled += name
            throw e
        } finally {
            running.decrementAndGet()
        }
        return name
    }

    override suspend fun views(s: Session): List<LibraryView> { enter("views"); return viewsFor(s) }

    override suspend fun children(
        s: Session, parentId: String, start: Int, limit: Int, sort: Sort, filter: ItemFilter, kinds: Set<ItemKind>, recursive: Boolean,
    ): Page<ItemWithUserData> {
        enter("children", "$start:$limit:${ListShape(kinds, recursive).key}")
        return childPage(parentId, start, limit)
    }

    override suspend fun item(s: Session, itemId: String): ItemWithUserData { enter("item", itemId); return itemFor(itemId) }

    override suspend fun seasons(s: Session, seriesId: String): List<ItemWithUserData> { enter("seasons"); return seasonsFor(seriesId) }

    override suspend fun episodes(s: Session, seriesId: String, seasonId: String?): List<ItemWithUserData> {
        enter("episodes", seasonId ?: "all")
        return episodesFor(seriesId, seasonId)
    }

    override suspend fun resume(s: Session, limit: Int): List<ItemWithUserData> { enter("resume", "$limit"); return resumeFor(s) }

    override suspend fun nextUp(s: Session, limit: Int): List<ItemWithUserData> { enter("nextUp", "$limit"); return nextUpFor(s) }

    override suspend fun latest(s: Session, viewId: String, limit: Int): List<ItemWithUserData> { enter("latest", "$limit"); return latestFor(s, viewId) }

    override suspend fun search(s: Session, term: String, limit: Int): List<ItemWithUserData> { enter("search", term); return searchFor(term) }

    override suspend fun setPlayed(s: Session, itemId: String, played: Boolean) { enter("setPlayed", "$itemId:$played") }

    override suspend fun setFavourite(s: Session, itemId: String, favourite: Boolean) { enter("setFavourite", "$itemId:$favourite") }

    override suspend fun clearResume(s: Session, itemId: String) { enter("clearResume", itemId) }
}
