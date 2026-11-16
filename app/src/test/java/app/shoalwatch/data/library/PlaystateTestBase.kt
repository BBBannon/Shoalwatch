package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.library.UserDataEntity
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.sync.OutboxStore
import app.shoalwatch.data.sync.ServerUserData
import app.shoalwatch.data.sync.SyncSender
import app.shoalwatch.data.sync.UserDataUpdate
import app.shoalwatch.data.sync.api.SyncGateway
import java.util.concurrent.ConcurrentHashMap
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

/**
 * The sync gateway the tidy-up tests use: every send reaches [server] (the [FakeLibraryGateway]) as the mutation it stands for,
 * so it is logged, held and failed there like before Milestone 5: PLAYED is `setPlayed`, FAVOURITE `setFavourite`, POSITION 0
 * `clearResume`; a position is `setPosition:<id>:<ms>` and a play `play:<id>`. The server's copy is [copies] (unknown by default).
 */
internal class LibrarySync(private val server: FakeLibraryGateway) : SyncGateway {
    val copies = ConcurrentHashMap<Pair<Session, String>, ServerUserData>()
    val gone: MutableSet<String> = ConcurrentHashMap.newKeySet()

    override suspend fun userData(s: Session, itemId: String): ServerUserData? = copies[s to itemId]

    override suspend fun update(s: Session, itemId: String, body: UserDataUpdate) = when {
        body.played == null -> if (body.positionMs == 0L) server.clearResume(s, itemId) else server.other("setPosition", "$itemId:${body.positionMs}")
        body.lastPlayedAt != null -> server.other("play", itemId)
        else -> server.setPlayed(s, itemId, body.played)
    }

    override suspend fun markPlayed(s: Session, itemId: String) = server.setPlayed(s, itemId, true)
    override suspend fun markUnplayed(s: Session, itemId: String) = server.setPlayed(s, itemId, false)
    override suspend fun favourite(s: Session, itemId: String, on: Boolean) = server.setFavourite(s, itemId, on)
    override suspend fun itemGone(s: Session, itemId: String): Boolean = itemId in gone
}

/**
 * The real [PlaystateRepository] on the real [LibraryRepository], a real in-memory Room, the real outbox, sender and
 * [PlaystateLanding], and the [FakeLibraryGateway] as the server (through [LibrarySync]). Every drain runs in the app scope, so
 * [settle] waits for the sends too.
 */
abstract class PlaystateTestBase : LibraryRepositoryTestBase() {
    internal lateinit var playstate: PlaystateRepository
    internal lateinit var outbox: OutboxStore
    internal lateinit var sync: LibrarySync
    internal lateinit var sender: SyncSender

    /** Every session [PlaystateRepository] said had a new row, in order. */
    protected val changedFor = java.util.concurrent.CopyOnWriteArrayList<Session>()
    protected val itemDao get() = db.itemDao()
    protected val listDao get() = db.listDao()

    @Before fun openPlaystate() {
        gateway.clockTick = { now += 1 } // a server round trip takes a moment, so what is asked after an action is strictly later than it
        outbox = OutboxStore(db) { now }
        sync = LibrarySync(gateway)
        sender = SyncSender(outbox, sync, PlaystateLanding(repo, outbox, db.offlinePlayDao()) { now }, { true }, {}, appScope, { now }, Dispatchers.IO)
        playstate = newPlaystate()
    }

    /** A repository over the same database and outbox (a "new process" needs a new one of everything else too). */
    internal fun newPlaystate() = PlaystateRepository(repo, outbox, { s -> changedFor += s; sender.kick(s) }, appScope, Dispatchers.IO, { now })

    /** Rows waiting in the outbox (every profile). */
    protected fun queued() = count("sync_outbox")

    protected fun ud(played: Boolean = false, positionMs: Long = 0, favourite: Boolean = false, playCount: Int = if (played) 1 else 0, unplayed: Int? = null, lastPlayed: Long? = null) =
        UserData(played, positionMs, null, playCount, favourite, lastPlayed?.let(java.time.Instant::ofEpochMilli), unplayed)

    protected fun save(vararg rows: ItemWithUserData, s: Session = s1u1) = runBlocking { library.writeItems(s, rows.toList(), t0, requestedAtSeq = 0) }

    protected fun saveList(key: String, vararg rows: ItemWithUserData, s: Session = s1u1) =
        runBlocking { library.writePage(s, key, 0, rows.toList(), rows.size, t0, requestedAtSeq = 0) }

    protected fun saveResume(vararg rows: ItemWithUserData) = saveList(ListKey.RESUME, *rows)

    protected fun series(label: String = "show", data: UserData? = ud(unplayed = 10)) = row(item(label, ItemKind.SERIES, "Series $label"), data)

    protected fun season(label: String, number: Int, data: UserData? = ud(unplayed = 3), series: String = "show") =
        row(item(label, ItemKind.SEASON, "Season $label", seriesId = series, indexNumber = number, childCount = 3), data)

    protected fun ep(label: String, season: String, number: Int, data: UserData? = ud(), series: String = "show") =
        row(item(label, ItemKind.EPISODE, "Episode $label", seriesId = series, seasonId = season, indexNumber = number, parentIndexNumber = season.filter { it.isDigit() }.toIntOrNull() ?: 1), data)

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

    /** Holds only the gateway calls whose name starts with [prefix], until the returned latch is released. */
    protected fun holdCalls(prefix: String): Latch = latch().also { l -> gateway.onCall = { call -> if (call.startsWith(prefix)) l.await() } }

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
