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

    /** Tasks 6-8 review M2: a newer unsent change of the same kind (here: off Continue Watching, then unwatched) is never replaced by an older recovered one. */
    @Test fun recoveryNeverReplacesANewerChangeOfTheSameKind() = runBlocking {
        save(film("a"), film("b"))
        saveResume(film("a"))
        gateway.failAll = AppError.Kind.OFFLINE
        dao.position(s1u1.serverId, s1u1.userId, id("a"), 600_000, now) // a LOCAL play whose stop never got queued
        dao.stopped(s1u1.serverId, s1u1.userId, id("b"), 5_000_000, finished = true, nowMs = now)
        now += 60_000
        playstate.removeFromContinueWatching(s1u1, id("a")) // later: POSITION 0
        playstate.setPlayed(s1u1, id("b"), false) // later: PLAYED false
        settle()
        assertEquals(0, plays.recoverUnqueued())
        val rows = db.openHelper.readableDatabase.query("SELECT kind, flag, positionMs FROM sync_outbox ORDER BY seq").use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getString(0), if (c.isNull(1)) null else c.getInt(1), if (c.isNull(2)) null else c.getLong(2))) }
        }
        assertEquals(listOf(Triple("POSITION", null, 0L), Triple("PLAYED", 0, null)), rows)
    }

    @Test fun anOlderChangeOfTheSameKindIsStillReplacedByTheRecoveredOne() = runBlocking {
        save(film("a"))
        saveResume(film("a"))
        gateway.failAll = AppError.Kind.OFFLINE
        playstate.removeFromContinueWatching(s1u1, id("a"))
        settle()
        now += 60_000
        dao.position(s1u1.serverId, s1u1.userId, id("a"), 600_000, now) // played after that, and the process died
        assertEquals(1, plays.recoverUnqueued())
        assertEquals(listOf("POSITION"), kinds())
        assertEquals(600_000L, db.openHelper.readableDatabase.query("SELECT positionMs FROM sync_outbox").use { it.moveToFirst(); it.getLong(0) })
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

    /**
     * Final review I2: an offline play's POSITION row replaced by a later change (here "Remove from Continue Watching") left its
     * offline_plays row uncovered, so the next start queued the old position again and put the item back in Continue Watching.
     * Any confirmed PLAYED, POSITION or PLAY row now clears the offline plays it covers.
     */
    @Test fun aRemovalThatReplacedAnOfflinePlaysPositionIsNotUndoneAfterARestart() = runBlocking {
        save(film("a"))
        saveResume(film("a"))
        gateway.failAll = AppError.Kind.OFFLINE
        dao.stopped(s1u1.serverId, s1u1.userId, id("a"), 600_000, finished = false, nowMs = now) // played part way in airplane mode
        playstate.recordLocalStop(s1u1, id("a"), 600_000, finished = false)
        settle()
        now += 60_000
        playstate.removeFromContinueWatching(s1u1, id("a")) // still offline: POSITION 0 replaces the offline play's row
        settle()
        assertEquals(listOf("POSITION"), kinds())
        gateway.failAll = null // online again
        sender.kick(s1u1).await()
        settle()
        assertEquals(0, queued())
        assertNull("the newer confirmed change covers the play", dao.get(s1u1.serverId, s1u1.userId, id("a")))
        assertEquals("the restart queues nothing", 0, plays.recoverUnqueued())
        assertEquals(0, queued())
    }

    @Test fun aConfirmedFavouriteDoesNotCoverAnOfflinePlay() = runBlocking {
        save(film("a"))
        gateway.failAll = AppError.Kind.OFFLINE
        dao.position(s1u1.serverId, s1u1.userId, id("a"), 600_000, now) // died mid-play: nothing queued for it
        now += 60_000
        playstate.setFavourite(s1u1, id("a"), true)
        settle()
        gateway.failAll = null
        sender.kick(s1u1).await()
        settle()
        assertEquals("a favourite says nothing about where playback stopped", 1, plays.recoverUnqueued())
    }
}
