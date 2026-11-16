package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.library.LocalChangeLog
import app.shoalwatch.data.sync.OutboxStore
import app.shoalwatch.data.sync.SyncSender
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every action writes its local change AND its outbox row in one transaction, keeps both until the server has it, and survives a restart. */
class PlaystateOutboxTest : PlaystateTestBase() {
    private data class Row(val kind: String, val flag: Int?, val positionMs: Long?, val refreshKeys: String, val fromOfflinePlay: Boolean)

    private fun rows(): List<Row> = db.openHelper.readableDatabase.query("SELECT kind, flag, positionMs, refreshKeys, fromOfflinePlay FROM sync_outbox ORDER BY seq").use { c ->
        buildList {
            while (c.moveToNext()) {
                add(Row(c.getString(0), if (c.isNull(1)) null else c.getInt(1), if (c.isNull(2)) null else c.getLong(2), c.getString(3), c.getInt(4) == 1))
            }
        }
    }

    /** (itemId, leftList) for every mark: "" is a plain mark. */
    private fun marks(): Set<Pair<String, String>> = db.openHelper.readableDatabase.query("SELECT itemId, leftList FROM sync_marks").use { c ->
        buildSet { while (c.moveToNext()) add(c.getString(0) to c.getString(1)) }
    }

    private fun offline() { gateway.failAll = AppError.Kind.OFFLINE }

    private fun reset() = runBlocking {
        settle()
        sql("DELETE FROM sync_outbox")
        gateway.calls.clear()
    }

    @Test fun eachActionQueuesExactlyItsChangeWithItsMarksDeparturesAndRefreshKeys() = runBlocking {
        saveResume(film("a"))
        offline()
        val a = id("a")

        playstate.setPlayed(s1u1, a, true)
        settle()
        assertEquals(listOf(Row("PLAYED", 1, null, "resume,nextUp", false)), rows())
        assertEquals(setOf(a to "", a to ListKey.RESUME), marks())
        reset()

        playstate.setFavourite(s1u1, a, true)
        settle()
        assertEquals(listOf(Row("FAVOURITE", 1, null, "", false)), rows())
        assertEquals(setOf(a to ""), marks())
        reset()

        saveResume(film("a"))
        playstate.removeFromContinueWatching(s1u1, a)
        settle()
        assertEquals(listOf(Row("POSITION", null, 0, "resume", false)), rows())
        assertEquals(setOf(a to "", a to ListKey.RESUME), marks())
        reset()

        playstate.recordStop(s1u1, a, 7_000, finished = true, reached = false)
        settle()
        assertEquals("never a PLAY for a stop the server may count itself", listOf(Row("PLAYED", 1, null, "resume,nextUp", false)), rows())
        reset()

        playstate.recordStop(s1u1, a, 7_000, finished = false, reached = false)
        settle()
        assertEquals(listOf(Row("POSITION", null, 7_000, "resume,nextUp", false)), rows())
        reset()

        playstate.recordStop(s1u1, a, 8_000, finished = false, reached = true)
        settle()
        assertEquals("the stop report reached the server", emptyList<Row>(), rows())

        playstate.recordStop(s1u1, a, 8_000, finished = true, reached = true)
        settle()
        assertEquals("finished is always a PLAYED tick (review I1), never a PLAY", listOf(Row("PLAYED", 1, null, "resume,nextUp", false)), rows())
        reset()

        playstate.recordLocalStop(s1u1, a, 9_000, finished = false)
        settle()
        assertEquals(listOf(Row("POSITION", null, 9_000, "resume,nextUp", true)), rows())
        reset()

        playstate.recordLocalStop(s1u1, a, 0, finished = true)
        settle()
        assertEquals(listOf(Row("PLAY", null, null, "resume,nextUp", true)), rows())
        assertEquals(8, changedFor.size) // every queued change asked for a send; the reached unfinished stop did not
    }

    @Test fun aFailureAfterTheLocalWriteLeavesNeitherTheChangeNorTheRow() = runBlocking {
        save(film("a", ud(favourite = false)))
        sql("CREATE TRIGGER nope BEFORE INSERT ON sync_outbox BEGIN SELECT RAISE(ABORT, 'no'); END")
        val e = failure { playstate.setFavourite(s1u1, id("a"), true) }
        assertEquals(AppError.Kind.UNKNOWN, e.kind)
        assertFalse(userData("a")!!.favourite)
        assertNoPending()
        assertEquals(0, queued())
        assertTrue(changedFor.isEmpty())
    }

    @Test fun offlineTheChangeStaysOnScreenAndTheRowInTheOutbox() = runBlocking {
        saveResume(film("a"), film("b"))
        offline()
        playstate.removeFromContinueWatching(s1u1, id("a"))
        settle()
        assertEquals(ids("b"), resumeIds())
        assertEquals(0, userData("a")!!.positionMs)
        assertNotNull(userData("a")!!.pendingSyncAt)
        assertEquals(1, queued())
    }

    @Test fun watchedThenUnwatchedQuicklyLeavesOneRowTheUnwatchedOne() = runBlocking {
        save(film("a"))
        offline()
        playstate.setPlayed(s1u1, id("a"), true)
        playstate.setPlayed(s1u1, id("a"), false)
        settle()
        assertEquals(listOf(Row("PLAYED", 0, null, "resume,nextUp", false)), rows())
    }

    @Test fun aSeasonTickIsOneRowMarkingItsEpisodesAndAnEpisodeTickAfterItIsASecondRow() = runBlocking {
        save(series(), season("s1", 1), ep("a", "s1", 1), ep("b", "s1", 2))
        offline()
        playstate.setPlayed(s1u1, id("s1"), true)
        settle()
        assertEquals(1, queued())
        assertEquals(setOf(id("s1"), id("a"), id("b")), marks().filter { it.second == "" }.map { it.first }.toSet())
        playstate.setPlayed(s1u1, id("a"), false)
        settle()
        assertEquals(2, queued())
    }

    @Test fun afterARestartTheSweepClearsNoMarkedRowAndAStaleContinueWatchingCannotBringTheItemBack() = runBlocking {
        saveResume(film("a"), film("b"))
        offline()
        playstate.removeFromContinueWatching(s1u1, id("a"))
        settle()

        // A new process: a new change log (its memory is gone), library, outbox, sender and repository over the same database.
        val nextChanges = LocalChangeLog(mono)
        val nextLibrary = LibraryRepository(gateway, db, appScope, Dispatchers.IO, { now }, log, nextChanges)
        val nextOutbox = OutboxStore(db) { now }
        val nextSender = SyncSender(nextOutbox, sync, PlaystateLanding(nextLibrary, nextOutbox, db.offlinePlayDao()) { now }, { true }, {}, appScope, { now }, Dispatchers.IO)
        gateway.failAll = null
        gateway.failOps["clearResume"] = AppError.Kind.OFFLINE // still not sent
        gateway.resumeFor = { listOf(film("a"), film("b")) } // the server still has it
        nextLibrary.refresh(s1u1, RefreshTarget.Home) // the sweep runs first, then the refresh
        assertNotNull("the sweep spares a row the outbox will send", userData("a")!!.pendingSyncAt)
        assertEquals("the unsent change keeps it out of the list", ids("b"), resumeIds())
        assertEquals(0, userData("a")!!.positionMs)

        gateway.failOps.clear()
        gateway.resumeFor = { listOf(film("b")) } // now the server has it
        nextSender.kick(s1u1).await()
        settle()
        assertEquals(0, queued())
        assertNull(userData("a")!!.pendingSyncAt)
        assertEquals(ids("b"), resumeIds())
    }
}
