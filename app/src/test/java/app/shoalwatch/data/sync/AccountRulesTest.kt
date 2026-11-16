package app.shoalwatch.data.sync

import app.shoalwatch.S1
import app.shoalwatch.S2
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.data.db.LibraryTestBase
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.data.servers.Session
import app.shoalwatch.security.SecretBox
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import javax.crypto.KeyGenerator

/**
 * The outbox rules for accounts, through the real [ServerStore] with the app's hooks: sign-out and removal take a profile's rows
 * with the account (and stop its drain), a 401 keeps them unsent until the next sign-in, and another profile is never touched.
 */
class AccountRulesTest : LibraryTestBase() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private lateinit var store: ServerStore
    private lateinit var outbox: OutboxStore
    private lateinit var gateway: FakeSyncGateway
    private lateinit var sender: SyncSender

    @Before fun openStore() {
        outbox = OutboxStore(db, System::currentTimeMillis)
        gateway = FakeSyncGateway()
        val landing = object : SyncLanding {
            override suspend fun confirmed(s: Session, seq: Long) { outbox.take(seq) }
            override suspend fun overruled(s: Session, seq: Long) { outbox.take(seq) }
        }
        // The hooks as AppGraph wires them (Task 10): forget on account gone and server removed; a sign-in kicks.
        store = ServerStore(
            db, SecretBox { key },
            onServerRemoved = { sender.forget(it) },
            onAccountGone = { s, u -> sender.forget(s, u) },
            onAccountSignedIn = { s, u -> sender.kick(Session(s, u)) },
        )
        sender = SyncSender(outbox, gateway, landing, { store.cachedToken(it.serverId, it.userId) != null }, store::awaitWarmed, scope, io = Dispatchers.Default)
        runBlocking {
            // The same accounts LibraryTestBase saved, sealed with this store's key.
            store.upsertAccount(Account(S1, U1, "ann", "tok-1"), makeActive = true)
            store.upsertAccount(Account(S1, U2, "bob", "tok-2"), makeActive = false)
            store.upsertAccount(Account(S2, U1, "ann", "tok-3"), makeActive = false)
            store.warmTokens()
            // Each sign-in above kicked a drain on [scope]. Let them end before a test writes rows or sets the gateway's gate, or one
            // of them sends a test's rows early (and ungated). A kick joins a live drain, or queues behind one that is ending (the
            // profile lock), so awaiting it means none is left.
            withTimeout(10_000) { listOf(s1u1, s1u2, s2u1).forEach { sender.kick(it).await() } }
        }
        listOf(s1u1, s1u2, s2u1).forEach { assertTrue("no drain is left from the sign-ins", !sender.isDraining(it)) }
    }

    @After fun stopScope() = scope.cancel()

    private fun enqueue(s: Session, label: String): Long = runBlocking {
        val item = id(label)
        outbox.enqueue(s, Change(item, ItemKind.MOVIE, ChangeKind.FAVOURITE, flag = true, occurredAt = 1), OutboxStore.Marks(listOf(item), emptyList()), listOf("resume"))
    }

    private fun drain(s: Session) = runBlocking { withTimeout(10_000) { sender.kick(s).await() } }

    private fun rows(s: Session, table: String) = count(table, "serverId = '${s.serverId}' AND userId = '${s.userId}'")

    private fun tombstone(s: Session, label: String) =
        sql("INSERT INTO released_episodes (serverId, userId, itemId, seriesId, seasonId, releasedAt) VALUES (?, ?, ?, ?, NULL, 1)", s.serverId, s.userId, id(label), id("show"))

    @Test fun signingOutDeletesExactlyThatProfilesRowsMarksSyncStateAndTombstonesAndTheOtherKeepsSending() {
        enqueue(s1u1, "a"); enqueue(s1u2, "b")
        runBlocking { outbox.touchSynced(s1u1); outbox.touchSynced(s1u2) }
        tombstone(s1u1, "e1"); tombstone(s1u2, "e2")
        runBlocking { store.removeAccount(S1, U1) }
        for (table in listOf("sync_outbox", "sync_marks", "sync_state", "released_episodes")) {
            assertEquals("$table of the signed-out profile", 0, rows(s1u1, table))
            assertEquals("$table of the other profile", 1, rows(s1u2, table))
        }
        assertEquals(DrainResult.EMPTY, drain(s1u2))
        assertEquals(listOf(s1u2), gateway.calls.map { it.session })
    }

    @Test fun aRejectedSignInKeepsTheRowsAndSendsNothingAndSigningInAgainSendsThemInOrder() {
        enqueue(s1u1, "a"); enqueue(s1u1, "b"); enqueue(s1u2, "c")
        runBlocking { store.invalidateToken(S1, U1) }
        assertEquals(DrainResult.WAITING_FOR_SIGN_IN, drain(s1u1))
        assertEquals("kept", 2, rows(s1u1, "sync_outbox"))
        assertTrue("nothing sent as that profile", gateway.calls.none { it.session == s1u1 })
        assertEquals("the other profile is untouched", DrainResult.EMPTY, drain(s1u2))
        runBlocking { store.upsertAccount(Account(S1, U1, "ann", "tok-1b"), makeActive = true) } // the hook kicks
        runBlocking { withTimeout(10_000) { while (rows(s1u1, "sync_outbox") > 0) delay(5) } }
        assertEquals(listOf(id("a"), id("b")), gateway.calls.filter { it.session == s1u1 }.map { it.itemId })
    }

    @Test fun removingTheServerDeletesEveryProfilesRowsAndNoOtherServers() {
        enqueue(s1u1, "a"); enqueue(s1u2, "b"); enqueue(s2u1, "c")
        runBlocking { store.removeServer(S1) }
        assertEquals(0, count("sync_outbox", "serverId = '$S1'"))
        assertEquals(0, count("sync_marks", "serverId = '$S1'"))
        assertEquals(1, rows(s2u1, "sync_outbox"))
    }

    @Test fun aDrainInFlightWhenTheAccountIsRemovedSendsNothingMore() {
        enqueue(s1u1, "a"); enqueue(s1u1, "b"); enqueue(s1u1, "c")
        val gate = CompletableDeferred<Unit>()
        gateway.gate = gate
        val applied = java.util.concurrent.atomic.AtomicInteger()
        gateway.onCall = { applied.incrementAndGet() } // past the gate: the request went out
        val result = sender.kick(s1u1)
        runBlocking { withTimeout(10_000) { while (gateway.arrivals.get() < 1) delay(5) } }
        runBlocking { store.removeAccount(S1, U1) }
        gate.complete(Unit)
        runBlocking { withTimeout(10_000) { result.await() } }
        assertEquals("the held request was cancelled with the drain, and none followed", 0, applied.get())
        assertEquals(1, gateway.calls.size)
        assertEquals(0, rows(s1u1, "sync_outbox"))
    }

    @Test fun aServerRemovedMidDrainSendsNothingMoreForAnyOfItsProfiles() {
        enqueue(s1u1, "a"); enqueue(s1u1, "b"); enqueue(s1u2, "c"); enqueue(s1u2, "d")
        val gate = CompletableDeferred<Unit>()
        gateway.gate = gate
        val applied = java.util.concurrent.atomic.AtomicInteger()
        gateway.onCall = { applied.incrementAndGet() }
        val one = sender.kick(s1u1)
        val two = sender.kick(s1u2)
        runBlocking { withTimeout(10_000) { while (gateway.arrivals.get() < 2) delay(5) } }
        runBlocking { store.removeServer(S1) }
        gate.complete(Unit)
        runBlocking { withTimeout(10_000) { one.await(); two.await() } }
        assertEquals("both held requests were cancelled with their drains", 0, applied.get())
        assertEquals(2, gateway.calls.size)
    }
}
