package app.shoalwatch.data.sync

import android.database.sqlite.SQLiteException
import app.shoalwatch.core.AppError
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.shadows.ShadowLog

/** `SyncSender`: in order, once, a lost reply never counted twice, one drain per profile, each only as itself. */
class SyncSenderTest : SyncSenderTestBase() {
    @Test fun fiveRowsAreSentInSeqOrderEachOnce() {
        val seqs = listOf("a", "b", "c", "d", "e").map { enqueue(s1u1, favourite(it)) }
        assertEquals(DrainResult.EMPTY, drain())
        assertEquals(listOf("a", "b", "c", "d", "e").map { "favourite" to id(it) }, gateway.calls.map { it.op to it.itemId })
        assertEquals(seqs, landing.confirmed)
        assertEquals(List(5) { true }, landing.taken)
        assertEquals(0, count("sync_outbox"))
        assertEquals(clock, runBlocking { outbox.status(s1u1, flowOf(true)).first() }.lastSyncedAt)
    }

    @Test fun aLostReplyIsConfirmedByTheNextDrainWithoutASecondUpdate() {
        val film = id("film")
        gateway.copies[s1u1 to film] = ServerUserData(played = true, playCount = 2, positionMs = 0, favourite = false, lastPlayedAt = clock - 86_400_000)
        val seq = enqueue(s1u1, play("film"))
        gateway.applyThenFail("update", AppError.Kind.NETWORK)
        assertEquals(DrainResult.WAITING_FOR_CONNECTION, drain())
        assertEquals(3, gateway.copy(s1u1, film).playCount)
        assertTrue(row(seq)!!.sentOnce)
        assertEquals(3, row(seq)!!.sentPlayCount)

        assertEquals(DrainResult.EMPTY, drain())
        assertEquals(listOf("userData", "update", "userData"), gateway.ops())
        assertEquals("rose by exactly one", 3, gateway.copy(s1u1, film).playCount)
        assertEquals(listOf(seq), landing.confirmed)
    }

    @Test fun aPlayWhoseFirstReadFailedCountsFromTheNextDrainsRead() {
        val film = id("film")
        gateway.copies[s1u1 to film] = ServerUserData(false, 2, 0, false, null)
        val seq = enqueue(s1u1, play("film"))
        gateway.failNext("userData", AppError.Kind.TIMEOUT)
        assertEquals(DrainResult.WAITING_FOR_CONNECTION, drain())
        assertFalse("nothing was sent", row(seq)!!.sentOnce)
        assertNull(row(seq)!!.sentPlayCount)
        gateway.copies[s1u1 to film] = ServerUserData(true, 5, 0, false, null) // another device played it meanwhile
        assertEquals(DrainResult.EMPTY, drain())
        assertEquals(6, gateway.calls.last { it.op == "update" }.body!!.playCount)
    }

    @Test fun aRowReplacedWhileInFlightIsPassedAndTheNewRowGoesNext() {
        val film = id("film")
        val old = enqueue(s1u1, played("film", true))
        var replacement = 0L
        gateway.onCall = { call -> if (call.op == "update" && replacement == 0L) {
                replacement = outbox.enqueue(s1u1, played("film", false), OutboxStore.Marks(listOf(film), emptyList()), emptyList())
            } }
        assertEquals(DrainResult.EMPTY, drain())
        assertEquals(listOf("userData", "update", "userData", "markUnplayed"), gateway.ops())
        assertEquals(listOf(old, replacement), landing.confirmed)
        assertEquals("the old confirm found nothing", listOf(false, true), landing.taken)
        assertFalse(gateway.copy(s1u1, film).played)
    }

    @Test fun kickTwiceJoinsOneDrainAndAProfileNeverDrainsTwiceAtOnce() {
        enqueue(s1u1, favourite("a"))
        val gate = CompletableDeferred<Unit>()
        gateway.gate = gate
        val first = sender.kick(s1u1)
        awaitHeld(1)
        enqueue(s1u1, favourite("b"))
        assertSame(first, sender.kick(s1u1))
        assertEquals(1, gateway.calls.size)
        gate.complete(Unit)
        assertEquals(DrainResult.EMPTY, runBlocking { withTimeout(10_000) { first.await() } })
        assertEquals(listOf(id("a"), id("b")), gateway.calls.map { it.itemId })
    }

    @Test fun twoProfilesDrainInParallelEachOnlyAsItself() {
        val mine = listOf("a", "b", "c").map { enqueue(s1u1, played("u1-$it")); id("u1-$it") }
        val theirs = listOf("a", "b", "c").map { enqueue(s1u2, played("u2-$it")); id("u2-$it") }
        val gate = CompletableDeferred<Unit>()
        gateway.gate = gate
        val a = sender.kick(s1u1)
        val b = sender.kick(s1u2)
        awaitHeld(2) // both are in flight at the same moment
        gate.complete(Unit)
        runBlocking { withTimeout(10_000) { assertEquals(DrainResult.EMPTY, a.await()); assertEquals(DrainResult.EMPTY, b.await()) } }
        for (call in gateway.calls) assertEquals(if (call.itemId in mine) s1u1 else s1u2, call.session)
        assertEquals(mine.toSet() + theirs.toSet(), gateway.calls.map { it.itemId }.toSet())
    }

    @Test fun drainAllDrainsEveryProfileWithRowsAndGivesTheWorstResult() {
        enqueue(s1u1, favourite("a"))
        enqueue(s2u1, favourite("b"))
        signedOut += s2u1
        assertEquals(DrainResult.WAITING_FOR_SIGN_IN, runBlocking { sender.drainAll() })
        assertEquals(listOf(s1u1), gateway.calls.map { it.session })
        signedOut.clear()
        assertEquals(DrainResult.EMPTY, runBlocking { sender.drainAll() })
        assertEquals(DrainResult.EMPTY, runBlocking { sender.drainAll() })
    }

    @Test fun nothingIsLoggedWithAnIdAPositionOrADate() {
        ShadowLog.clear()
        val position = Change(id("film"), ItemKind.MOVIE, ChangeKind.POSITION, positionMs = 4_242_424, occurredAt = 1_777_777_777_777)
        enqueue(s1u1, position)
        gateway.failNext("userData", AppError.Kind.UNKNOWN)
        assertEquals(DrainResult.STOPPED, drain())
        gateway.failNext("userData", AppError.Kind.SERVER_ERROR)
        assertEquals(DrainResult.BACKING_OFF, drain())
        clock += 3_600_000
        landing.fail = SQLiteException("disk I/O error on ${id("film")}")
        assertEquals(DrainResult.STOPPED, drain())
        val logged = ShadowLog.getLogs().joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable?.message}" }
        assertTrue("something was logged", logged.contains("SyncSender"))
        for (secret in listOf(id("film"), s1u1.userId, s1u1.serverId, "4242424", "1777777777777", "disk I/O")) {
            assertFalse("logged $secret: $logged", secret in logged)
        }
    }

    @Test fun aLandingThatLeavesTheRowStopsTheDrainRatherThanLooping() {
        val seq = enqueue(s1u1, favourite("a"))
        val stubborn = object : SyncLanding {
            override suspend fun confirmed(s: Session, seq: Long) = Unit
            override suspend fun overruled(s: Session, seq: Long) = Unit
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val looping = SyncSender(outbox, gateway, stubborn, { true }, {}, scope, { clock }, Dispatchers.Default)
        assertEquals(DrainResult.STOPPED, runBlocking { withTimeout(10_000) { looping.kick(s1u1).await() } })
        assertEquals(1, gateway.calls.size)
        assertEquals(seq, row(seq)!!.seq)
        scope.cancel()
    }
}
