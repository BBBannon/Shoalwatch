package app.shoalwatch.data.sync

import app.shoalwatch.core.AppError
import app.shoalwatch.data.library.PlaystateTestBase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** At start, an offline play no outbox row covers (a process that died mid-play) is queued once, never as a PLAY, and goes once it is sent. */
class OfflineRecoveryTest : PlaystateTestBase() {
    private val plays get() = OfflinePlays(db.offlinePlayDao(), { now }, outbox, db.itemDao())
    private val dao get() = db.offlinePlayDao()

    private fun kinds(): List<String> = db.openHelper.readableDatabase.query("SELECT kind FROM sync_outbox ORDER BY seq").use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    @Test fun aPlayWithPositionsAndNoStopIsQueuedAsItsPosition() = runBlocking {
        save(film("a"))
        dao.position(s1u1.serverId, s1u1.userId, id("a"), 600_000, now) // the 10 s reports; then the process died
        gateway.failAll = AppError.Kind.OFFLINE
        assertEquals(1, plays.recoverUnqueued())
        assertEquals(listOf("POSITION"), kinds())
    }

    @Test fun aFinishedPlayWithNoRowIsQueuedAsPlayedNeverAsAPlay() = runBlocking {
        save(film("a"))
        dao.stopped(s1u1.serverId, s1u1.userId, id("a"), 5_000_000, finished = true, nowMs = now) // the first write; died before the second
        gateway.failAll = AppError.Kind.OFFLINE
        assertEquals(1, plays.recoverUnqueued())
        assertEquals("a PLAY could count the watch twice", listOf("PLAYED"), kinds())
    }

    @Test fun aPlayAQueuedRowCoversIsLeftAndRecoveringTwiceQueuesNothingNew() = runBlocking {
        save(film("a"), film("b"))
        gateway.failAll = AppError.Kind.OFFLINE
        dao.stopped(s1u1.serverId, s1u1.userId, id("a"), 5_000_000, finished = true, nowMs = now)
        playstate.recordLocalStop(s1u1, id("a"), 5_000_000, finished = true) // queued by the stop itself
        dao.position(s1u1.serverId, s1u1.userId, id("b"), 1_000, now)
        settle()
        assertEquals(1, plays.recoverUnqueued())
        assertEquals(0, plays.recoverUnqueued())
        assertEquals(listOf("PLAY", "POSITION"), kinds())
    }

    @Test fun onceTheRecoveredRowIsConfirmedTheOfflinePlayIsGone() = runBlocking {
        save(film("a"))
        dao.position(s1u1.serverId, s1u1.userId, id("a"), 600_000, now)
        plays.recoverUnqueued()
        sender.kick(s1u1).await()
        settle()
        assertEquals(0, queued())
        assertNull(dao.get(s1u1.serverId, s1u1.userId, id("a")))
        assertEquals(listOf("setPosition:${id("a")}:600000"), gateway.calls.filter { it.startsWith("setPosition") })
        assertEquals(0, plays.recoverUnqueued())
    }

    @Test fun aPlayWrittenAfterTheRowItsLandingCoversIsKept() = runBlocking {
        save(film("a"))
        dao.position(s1u1.serverId, s1u1.userId, id("a"), 600_000, now)
        plays.recoverUnqueued()
        now += 10_000
        dao.position(s1u1.serverId, s1u1.userId, id("a"), 700_000, now) // played on after the row was queued
        sender.kick(s1u1).await()
        settle()
        assertEquals("newer than what was sent: kept for the next recovery", 700_000L, dao.get(s1u1.serverId, s1u1.userId, id("a"))!!.positionMs)
    }
}
