package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.library.UserDataEntity
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.After
import org.junit.Before

/** The real [PlaystateRepository] on the real [LibraryRepository], a real in-memory Room and the [FakeLibraryGateway]. */
abstract class PlaystateTestBase : LibraryRepositoryTestBase() {
    protected lateinit var playstate: PlaystateRepository
    protected val itemDao get() = db.itemDao()
    protected val listDao get() = db.listDao()

    @Before fun openPlaystate() {
        playstate = PlaystateRepository(gateway, db, repo, appScope, Dispatchers.IO, { now })
    }

    protected fun ud(played: Boolean = false, positionMs: Long = 0, favourite: Boolean = false, playCount: Int = if (played) 1 else 0, unplayed: Int? = null, lastPlayed: Long? = null) =
        UserData(played, positionMs, null, playCount, favourite, lastPlayed?.let(java.time.Instant::ofEpochMilli), unplayed)

    protected fun save(vararg rows: ItemWithUserData, s: Session = s1u1) = runBlocking { library.writeItems(s, rows.toList(), t0) }

    protected fun saveList(key: String, vararg rows: ItemWithUserData, s: Session = s1u1) =
        runBlocking { library.writePage(s, key, 0, rows.toList(), rows.size, t0) }

    protected fun saveResume(vararg rows: ItemWithUserData) = saveList(ListKey.RESUME, *rows)

    protected fun series(label: String = "show", data: UserData? = ud(unplayed = 10)) = row(item(label, ItemKind.SERIES, "Series $label"), data)

    protected fun season(label: String, number: Int, data: UserData? = ud(unplayed = 3), series: String = "show") =
        row(item(label, ItemKind.SEASON, "Season $label", seriesId = series, indexNumber = number, childCount = 3), data)

    protected fun ep(label: String, season: String, number: Int, data: UserData? = ud(), series: String = "show") =
        row(item(label, ItemKind.EPISODE, "Episode $label", seriesId = series, seasonId = season, indexNumber = number, parentIndexNumber = 1), data)

    protected fun film(label: String, data: UserData? = ud(positionMs = 60_000)) = row(item(label), data)

    protected fun userData(label: String, s: Session = s1u1): UserDataEntity? = runBlocking { itemDao.getUserData(s.serverId, s.userId, id(label)) }

    protected fun resumeIds(s: Session = s1u1): List<String> = runBlocking { listDao.entryIds(s.serverId, s.userId, ListKey.RESUME) }

    protected fun ids(vararg labels: String) = labels.map { id(it) }

    protected fun pendingRows() = count("user_data", "pendingSyncAt IS NOT NULL")

    /** Holds the next server call until [release]. */
    protected fun holdServer(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { gateway.gate = it }

    protected fun release(held: CompletableDeferred<Unit>) { gateway.gate = null; held.complete(Unit) }

    private val latches = mutableListOf<Latch>()

    /** A latch that is opened at the end of the test whatever happens, so a failing test fails instead of hanging in teardown. */
    protected fun latch(): Latch = Latch().also { latches += it }

    /** Runs before the superclass's clean-up, which waits for background work to finish. */
    @After fun openLatches() { latches.forEach { it.release() } }

    /** Holds the server's answer to an action (setPlayed, setFavourite, clearResume) only; refreshes pass. [Latch.release] lets it go. */
    protected fun holdMutation(): Latch = latch().also { latch ->
        gateway.onCall = { call -> if (MUTATIONS.any { call.startsWith(it) }) latch.await() }
    }

    private val callers = CoroutineScope(Dispatchers.Default)

    /** Starts an action the way a screen would (its own scope, not the app scope); the Deferred is what the screen awaits. */
    protected fun <T> background(block: suspend () -> T): Deferred<T> = callers.async(start = CoroutineStart.UNDISPATCHED) { block() }

    /** The error a failing action throws; fails the test if it returns normally. */
    protected fun failure(action: suspend () -> Unit): AppError = runBlocking {
        try {
            action()
            fail("expected the action to fail")
            error("unreachable")
        } catch (e: AppError) {
            e
        }
    }

    /** [settle] for tests that are not coroutines. */
    protected fun drain() = runBlocking { settle() }

    protected fun assertNoPending() = assertEquals("rows still marked pending", 0, pendingRows())

    private companion object {
        val MUTATIONS = listOf("setPlayed", "setFavourite", "clearResume")
    }
}

/** A one-shot latch for holding one kind of gateway call (the fake's `gate` holds every call). */
class Latch {
    private val d = CompletableDeferred<Unit>()
    suspend fun await() = d.await()
    fun release() { d.complete(Unit) }
}
