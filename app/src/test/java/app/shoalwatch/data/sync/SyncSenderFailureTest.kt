package app.shoalwatch.data.sync

import app.shoalwatch.core.AppError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every row of the failure table: what happens to the row, and what the drain answers. No row is ever deleted for failing. */
class SyncSenderFailureTest : SyncSenderTestBase() {
    /** Two rows for different items; the first one's send fails with [kind]. */
    private fun firstFails(kind: AppError.Kind): Pair<Long, Long> {
        val a = enqueue(s1u1, favourite("a"))
        val b = enqueue(s1u1, favourite("b"))
        gateway.failNext("favourite", kind)
        return a to b
    }

    @Test fun noConnectionLeavesTheRowAndStopsInOrder() {
        for (kind in listOf(AppError.Kind.OFFLINE, AppError.Kind.NETWORK, AppError.Kind.TIMEOUT)) {
            val (a, _) = firstFails(kind)
            assertEquals(kind.name, DrainResult.WAITING_FOR_CONNECTION, drain())
            assertEquals("the next row was not sent", 1, gateway.calls.size)
            assertEquals(0, row(a)!!.serverErrors)
            assertEquals(0L, row(a)!!.notBefore)
            reset()
        }
    }

    /**
     * Tasks 6-8 review M1: the connection comes back while a request is failing on the old one, and the reconnection's kick joins
     * that drain. The drain goes round once more for it instead of stopping, so the row is sent now, not after the job's back-off.
     */
    @Test fun aKickThatJoinsADrainFailingOnTheOldConnectionSendsOnceMore() {
        enqueue(s1u1, favourite("a"))
        gateway.failNext("favourite", AppError.Kind.OFFLINE)
        var joined = false
        gateway.onCall = { if (!joined) { joined = true; sender.kick(s1u1) } } // the reconnection, during the failing request
        assertEquals(DrainResult.EMPTY, drain())
        assertEquals(listOf("favourite", "favourite"), gateway.ops())
        assertEquals(0, rawRows().size)
    }

    @Test fun withNoKickJoiningANoConnectionStopIsNotRetried() {
        enqueue(s1u1, favourite("a"))
        gateway.failNext("favourite", AppError.Kind.OFFLINE, times = 5)
        assertEquals(DrainResult.WAITING_FOR_CONNECTION, drain())
        assertEquals("once only: no loop while offline", 1, gateway.calls.size)
    }

    @Test fun aConnectionTheUserMustFixLeavesTheRowAndStops() {
        val kinds = listOf(AppError.Kind.TLS_UNTRUSTED, AppError.Kind.TLS_PIN_CHANGED, AppError.Kind.CLIENT_CERT_REQUIRED, AppError.Kind.CLEARTEXT_BLOCKED)
        for (kind in kinds) {
            val (a, _) = firstFails(kind)
            assertEquals(kind.name, DrainResult.WAITING_FOR_CONNECTION, drain())
            assertEquals(1, gateway.calls.size)
            assertEquals(0, row(a)!!.serverErrors)
            reset()
        }
    }

    @Test fun aServerErrorDefersTheRowAndHoldsTheProfile() {
        val (a, b) = firstFails(AppError.Kind.SERVER_ERROR)
        assertEquals(DrainResult.BACKING_OFF, drain())
        assertEquals(1, gateway.calls.size)
        assertEquals(1, row(a)!!.serverErrors)
        assertEquals(clock + 60_000, row(a)!!.notBefore)
        assertEquals(DrainResult.BACKING_OFF, drain())
        assertEquals("not due: nothing sent", 1, gateway.calls.size)
        clock += 60_000
        assertEquals(DrainResult.EMPTY, drain())
        assertEquals(listOf(a, b), landing.confirmed)
    }

    @Test fun tenServerErrorsParkTheRowAndTheNextItemsRowGoes() {
        val (a, b) = firstFails(AppError.Kind.SERVER_ERROR)
        gateway.failNext("favourite", AppError.Kind.SERVER_ERROR, times = OutboxStore.STUCK_AFTER - 1)
        repeat(OutboxStore.STUCK_AFTER) {
            assertEquals(DrainResult.BACKING_OFF, drain())
            clock += 7 * 3_600_000L
        }
        clock -= 7 * 3_600_000L
        assertEquals(listOf(b), landing.confirmed)
        assertEquals(OutboxStore.STUCK_AFTER, row(a)!!.serverErrors)
        assertEquals("still waiting, never dropped", listOf(a), rawRows().map { it.seq })
        clock += 6 * 3_600_000L
        assertEquals(DrainResult.EMPTY, drain())
        assertEquals(listOf(b, a), landing.confirmed)
    }

    @Test fun aRejectedSignInLeavesTheRowAndStops() {
        for (kind in listOf(AppError.Kind.UNAUTHORISED, AppError.Kind.NOT_SIGNED_IN)) {
            val (a, _) = firstFails(kind)
            assertEquals(kind.name, DrainResult.WAITING_FOR_SIGN_IN, drain())
            assertEquals(1, gateway.calls.size)
            assertEquals(a, row(a)!!.seq)
            reset()
        }
    }

    @Test fun forbiddenIsTheServersAnswerAboutThisItem() {
        val (a, b) = firstFails(AppError.Kind.FORBIDDEN)
        assertEquals(DrainResult.EMPTY, drain())
        assertEquals(listOf(a), landing.overruled)
        assertEquals(listOf(b), landing.confirmed)
    }

    @Test fun notFoundWithTheItemGoneIsOverruled() {
        val (a, b) = firstFails(AppError.Kind.SERVER_NOT_FOUND)
        gateway.gone += id("a")
        assertEquals(DrainResult.EMPTY, drain())
        assertEquals(listOf("favourite", "itemGone", "favourite"), gateway.ops())
        assertEquals(listOf(a), landing.overruled)
        assertEquals(listOf(b), landing.confirmed)
    }

    @Test fun notFoundWithTheItemNotGoneIsDeferredNeverDropped() {
        val (a, _) = firstFails(AppError.Kind.SERVER_NOT_FOUND)
        assertEquals(DrainResult.BACKING_OFF, drain())
        assertEquals(listOf("favourite", "itemGone"), gateway.ops())
        assertEquals(1, row(a)!!.serverErrors)
        assertTrue(landing.overruled.isEmpty())
    }

    @Test fun itemGoneFailingIsHandledByItsOwnKind() {
        val (a, _) = firstFails(AppError.Kind.SERVER_NOT_FOUND)
        gateway.failNext("itemGone", AppError.Kind.NETWORK)
        assertEquals(DrainResult.WAITING_FOR_CONNECTION, drain())
        assertEquals(0, row(a)!!.serverErrors)
    }

    @Test fun anythingElseStopsTheDrainAndKeepsTheRow() {
        val (a, _) = firstFails(AppError.Kind.UNKNOWN)
        assertEquals(DrainResult.STOPPED, drain())
        assertEquals(1, gateway.calls.size)
        assertEquals(0, row(a)!!.serverErrors)
    }

    @Test fun aLocalDatabaseFailureStopsTheDrainAndKeepsTheRow() {
        val a = enqueue(s1u1, favourite("a"))
        landing.fail = IllegalStateException("database is locked")
        assertEquals(DrainResult.STOPPED, drain())
        assertEquals(a, row(a)!!.seq)
    }

    @Test fun noTokenSendsNothingAtAll() {
        enqueue(s1u1, favourite("a"))
        enqueue(s1u1, play("b"))
        signedOut += s1u1
        assertEquals(DrainResult.WAITING_FOR_SIGN_IN, drain())
        assertTrue(gateway.calls.isEmpty())
        assertEquals(2, count("sync_outbox"))
    }

    @Test fun theTokenIsCheckedAgainBeforeTheSendThatFollowsARead() {
        val seq = enqueue(s1u1, play("film"))
        gateway.onCall = { if (it.op == "userData") signedOut += s1u1 }
        assertEquals(DrainResult.WAITING_FOR_SIGN_IN, drain())
        assertEquals(listOf("userData"), gateway.ops())
        assertEquals("the row stays", seq, row(seq)!!.seq)
    }

    @Test fun forgetDuringAHeldCallMeansNothingMoreIsSentForThatAccount() {
        enqueue(s1u1, favourite("a"))
        enqueue(s1u1, favourite("b"))
        enqueue(s1u2, favourite("c"))
        val gate = CompletableDeferred<Unit>()
        gateway.gate = gate
        val mine = sender.kick(s1u1)
        val theirs = sender.kick(s1u2)
        awaitHeld(2)
        sender.forget(s1u1.serverId, s1u1.userId)
        gate.complete(Unit)
        runBlocking { withTimeout(10_000) { mine.await(); assertEquals(DrainResult.EMPTY, theirs.await()) } }
        Thread.sleep(200)
        assertEquals(listOf(id("a")), gateway.calls.filter { it.session == s1u1 }.map { it.itemId })
        assertEquals("the other account goes on", listOf(id("c")), gateway.calls.filter { it.session == s1u2 }.map { it.itemId })
        assertTrue(mine.isCompleted)
    }

    @Test fun forgetAServerEndsEveryDrainOnIt() {
        enqueue(s1u1, favourite("a"))
        enqueue(s1u2, favourite("b"))
        enqueue(s2u1, favourite("c"))
        val gate = CompletableDeferred<Unit>()
        gateway.gate = gate
        val drains = listOf(s1u1, s1u2, s2u1).map(sender::kick)
        awaitHeld(3)
        sender.forget(s1u1.serverId)
        gate.complete(Unit)
        val results = runBlocking { withTimeout(10_000) { drains.map { it.await() } } }
        assertEquals(DrainResult.EMPTY, results[2])
        assertEquals(listOf(id("c")), gateway.calls.filter { it.session == s2u1 }.map { it.itemId })
        assertEquals("nothing on the forgotten server landed", 1, landing.confirmed.size)
    }

    private fun reset() = runBlocking {
        db.openHelper.writableDatabase.execSQL("DELETE FROM sync_outbox")
        gateway.calls.clear()
        landing.confirmed.clear()
        landing.overruled.clear()
    }
}
