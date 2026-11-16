package app.shoalwatch.data.sync

import app.shoalwatch.data.db.LibraryTestBase
import app.shoalwatch.data.db.sync.OutboxEntity
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** A [SyncSender] over the real [OutboxStore] (in-memory Room), a [FakeSyncGateway] and a landing that records each seq and takes the row. */
abstract class SyncSenderTestBase : LibraryTestBase() {
    internal class RecordingLanding(private val outbox: OutboxStore) : SyncLanding {
        val confirmed = CopyOnWriteArrayList<Long>()
        val overruled = CopyOnWriteArrayList<Long>()
        val taken = CopyOnWriteArrayList<Boolean>() // whether take still found the row
        @Volatile var fail: Exception? = null

        override suspend fun confirmed(s: Session, seq: Long) {
            fail?.let { throw it }
            confirmed += seq
            taken += outbox.take(seq) != null
        }

        override suspend fun overruled(s: Session, seq: Long) {
            fail?.let { throw it }
            overruled += seq
            taken += outbox.take(seq) != null
        }
    }

    protected var clock = 1_000_000L
    protected val signedOut: MutableSet<Session> = ConcurrentHashMap.newKeySet()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal lateinit var outbox: OutboxStore
    internal lateinit var gateway: FakeSyncGateway
    internal lateinit var landing: RecordingLanding
    internal lateinit var sender: SyncSender

    @Before fun openSender() {
        outbox = OutboxStore(db) { clock }
        gateway = FakeSyncGateway()
        landing = RecordingLanding(outbox)
        sender = SyncSender(outbox, gateway, landing, { it !in signedOut }, {}, scope, { clock }, Dispatchers.Default)
    }

    @After fun closeScope() = scope.cancel()

    internal fun enqueue(s: Session, change: Change): Long =
        runBlocking { outbox.enqueue(s, change, OutboxStore.Marks(listOf(change.itemId), emptyList()), listOf("resume")) }

    internal fun favourite(label: String, on: Boolean = true) = Change(id(label), ItemKind.MOVIE, ChangeKind.FAVOURITE, flag = on, occurredAt = clock)
    internal fun played(label: String, on: Boolean = true) = Change(id(label), ItemKind.MOVIE, ChangeKind.PLAYED, flag = on, occurredAt = clock)
    internal fun play(label: String) = Change(id(label), ItemKind.MOVIE, ChangeKind.PLAY, occurredAt = clock)

    internal fun drain(s: Session = s1u1): DrainResult = runBlocking { withTimeout(10_000) { sender.kick(s).await() } }

    /** A row as stored, past any hold (head() honours holds), or null when it has gone. */
    internal fun row(seq: Long): OutboxEntity? = rawRows().firstOrNull { it.seq == seq }

    internal fun rawRows(): List<OutboxEntity> =
        db.openHelper.readableDatabase.query(
            "SELECT seq, serverId, userId, itemId, kind, sentOnce, sentPlayCount, attempts, serverErrors, notBefore FROM sync_outbox ORDER BY seq",
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        OutboxEntity(
                            seq = c.getLong(0), serverId = c.getString(1), userId = c.getString(2), itemId = c.getString(3), itemKind = ItemKind.MOVIE,
                            kind = ChangeKind.valueOf(c.getString(4)), flag = null, positionMs = null, occurredAt = 0, refreshKeys = "",
                            fromOfflinePlay = false, sentOnce = c.getInt(5) == 1, sentPlayCount = if (c.isNull(6)) null else c.getInt(6),
                            attempts = c.getInt(7), serverErrors = c.getInt(8), notBefore = c.getLong(9),
                        ),
                    )
                }
            }
        }

    /** Waits (in real time) until [n] calls are held at the gate. */
    internal fun awaitHeld(n: Int) = runBlocking { withTimeout(10_000) { while (gateway.arrivals.get() < n) delay(5) } }
}
