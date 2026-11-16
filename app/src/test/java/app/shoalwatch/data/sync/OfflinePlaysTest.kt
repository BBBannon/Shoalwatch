package app.shoalwatch.data.sync

import app.shoalwatch.data.db.DownloadTestBase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `offline_plays`: per profile, watched never undone, gone with the account, recovered into the outbox. */
class OfflinePlaysTest : DownloadTestBase() {
    private val plays get() = OfflinePlays(db.offlinePlayDao(), { clock }, OutboxStore(db) { clock }, db.itemDao())
    private val film get() = id("film")

    private fun row(s: app.shoalwatch.data.servers.Session) = runBlocking { db.offlinePlayDao().get(s.serverId, s.userId, film) }

    @Test fun positionsAreKeptPerProfile() = runBlocking {
        plays.position(s1u1, film, 10_000)
        plays.position(s1u2, film, 99_000)
        clock = 2_000
        plays.position(s1u1, film, 20_000)
        assertEquals(20_000L, row(s1u1)!!.positionMs)
        assertEquals(2_000L, row(s1u1)!!.lastPlayedAt)
        assertEquals(99_000L, row(s1u2)!!.positionMs)
        assertNull(row(s2u1))
        assertFalse(row(s1u1)!!.played)
    }

    @Test fun aFinishedStopSetsPlayedAndPlayedAtOnceAndALaterUnfinishedStopNeverUndoesIt() = runBlocking {
        plays.position(s1u1, film, 10_000)
        clock = 5_000
        plays.stopped(s1u1, film, 5_300_000, finished = true)
        assertTrue(row(s1u1)!!.played)
        assertEquals(5_000L, row(s1u1)!!.playedAt)
        clock = 9_000
        plays.stopped(s1u1, film, 60_000, finished = false)
        plays.stopped(s1u1, film, 70_000, finished = true)
        assertTrue("never cleared", row(s1u1)!!.played)
        assertEquals("set once", 5_000L, row(s1u1)!!.playedAt)
        assertEquals(70_000L, row(s1u1)!!.positionMs)
        assertEquals(9_000L, row(s1u1)!!.lastPlayedAt)
    }

    @Test fun aStopWithNoEarlierPositionStillWritesTheRow() = runBlocking {
        plays.stopped(s1u1, film, 42_000, finished = false)
        assertEquals(42_000L, row(s1u1)!!.positionMs)
    }

    @Test fun theRowsGoWithTheAccount() = runBlocking {
        plays.position(s1u1, film, 10_000)
        plays.position(s1u2, film, 10_000)
        servers.removeAccount(s1u1.serverId, s1u1.userId)
        assertNull(row(s1u1))
        assertEquals(1, count("offline_plays"))
    }

    @Test fun aRecoveredPlayIsQueuedAtItsOwnTimeWithItsMarkAndNeverAsAPlay() = runBlocking {
        clock = 5_000
        plays.stopped(s1u1, film, 5_300_000, finished = true)
        clock = 7_000
        plays.position(s1u2, film, 42_000)
        assertEquals(2, plays.recoverUnqueued())
        val rows = db.openHelper.readableDatabase.query("SELECT userId, kind, flag, positionMs, occurredAt, fromOfflinePlay, itemKind FROM sync_outbox ORDER BY seq").use { c ->
            buildList { while (c.moveToNext()) add(listOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getLong(4), c.getInt(5), c.getString(6))) }
        }
        assertEquals(
            listOf(
                listOf(s1u1.userId, "PLAYED", "1", null, 5_000L, 1, "OTHER"), // no item row: OTHER
                listOf(s1u2.userId, "POSITION", null, "42000", 7_000L, 1, "OTHER"),
            ),
            rows,
        )
        assertEquals(2, count("sync_marks", "leftList = ''"))
    }

    @Test fun aMalformedIdWritesNothing() = runBlocking {
        plays.position(s1u1, "not an id", 1)
        plays.stopped(s1u1, film.uppercase(), 1, finished = true)
        assertEquals(0, count("offline_plays"))
    }
}
