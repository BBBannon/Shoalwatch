package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where playback stopped, saved on this phone: a finished stop always queues a PLAYED tick; a position only when the stop report did not reach the server. */
class PlaystateRecordStopTest : PlaystateTestBase() {
    private fun stop(label: String, positionMs: Long, finished: Boolean = false, reached: Boolean = true) =
        runBlocking { playstate.recordStop(s1u1, id(label), positionMs, finished, reached) }

    @Test fun aPositionIsSavedWithNoServerCallAndTheItemComesBackInContinueWatching() {
        save(film("a", ud(positionMs = 0)))
        gateway.resumeFor = { listOf(film("a", ud(positionMs = 600_000))) } // the server heard the stop report
        stop("a", 600_000)
        drain()
        assertEquals(600_000, userData("a")!!.positionMs)
        assertFalse(userData("a")!!.played)
        assertEquals(0, gateway.count("setPlayed") + gateway.count("setFavourite") + gateway.count("clearResume"))
        assertTrue("resume and next up are asked again", gateway.count("resume") >= 1 && gateway.count("nextUp") >= 1)
        assertEquals(ids("a"), resumeIds())
        assertNoPending()
    }

    @Test fun theSavedPositionShowsAtOnceEvenIfTheRefreshFails() {
        save(film("a", ud(positionMs = 0)))
        gateway.failOps["resume"] = AppError.Kind.OFFLINE
        stop("a", 42_000)
        drain()
        assertEquals(42_000, userData("a")!!.positionMs)
        assertNoPending()
    }

    /**
     * Tasks 6-8 review I1: "Finished" fires at the Outro start, which can be below the server's 90% "played" mark, so a stop the
     * server heard leaves it unplayed with a resume point. A PLAYED tick is queued for every finished stop, so the server ends
     * played and the phone stays watched, out of Continue Watching.
     */
    @Test fun aFinishedStopAtTheOutroBelowNinetyPercentEndsPlayedOnTheServerToo() {
        val a = id("a")
        save(film("a", ud(positionMs = 60_000)))
        saveResume(film("a", ud(positionMs = 60_000)))
        // What the server did with the stop report at 87%: a resume point, not played.
        sync.copies[s1u1 to a] = app.shoalwatch.data.sync.ServerUserData(played = false, playCount = 0, positionMs = 6_090_000, favourite = false, lastPlayedAt = null)
        gateway.resumeFor = { if (gateway.count("setPlayed") > 0) emptyList() else listOf(film("a", ud(positionMs = 6_090_000))) }
        stop("a", 6_090_000, finished = true)
        drain()
        assertEquals("the server is told it was watched", listOf("setPlayed:$a:true"), gateway.calls.filter { it.startsWith("setPlayed") })
        assertEquals(0, queued())
        assertTrue("watched on the phone", userData("a")!!.played)
        assertEquals(emptyList<String>(), resumeIds())
        assertNoPending()
    }

    @Test fun aFinishedStopTheServerAlreadyCountedSendsNothingMore() {
        save(film("a", ud(positionMs = 60_000)))
        saveResume(film("a", ud(positionMs = 60_000)))
        sync.copies[s1u1 to id("a")] = app.shoalwatch.data.sync.ServerUserData(played = true, playCount = 1, positionMs = 0, favourite = false, lastPlayedAt = null)
        stop("a", 7_000_000, finished = true)
        drain()
        assertEquals("already there: no second write, so no second play", 0, gateway.count("setPlayed"))
        assertEquals(0, queued())
        assertTrue(userData("a")!!.played)
        assertEquals(0, userData("a")!!.positionMs)
        assertEquals(emptyList<String>(), resumeIds())
        assertNoPending()
    }

    @Test fun aFinishedStopTheServerMissedIsWatchedAtOnceAndAPlayedTickIsSent() {
        save(film("a", ud(positionMs = 60_000)))
        gateway.failOps["setPlayed"] = AppError.Kind.OFFLINE
        stop("a", 7_000_000, finished = true, reached = false)
        drain()
        assertTrue(userData("a")!!.played)
        assertEquals("PLAYED, never PLAY", 1, gateway.count("setPlayed"))
        assertEquals(0, gateway.count("play"))
        assertEquals(1, queued())
        gateway.failOps.clear()
        sender.kick(s1u1)
        drain()
        assertEquals(0, queued())
        assertNoPending()
    }

    @Test fun anUnfinishedStopTheServerMissedQueuesItsPosition() {
        save(film("a", ud(positionMs = 0)))
        stop("a", 420_000, reached = false)
        drain()
        assertEquals(420_000, userData("a")!!.positionMs)
        assertEquals(listOf("setPosition:${id("a")}:420000"), gateway.calls.filter { it.startsWith("setPosition") })
        assertEquals(0, queued())
        assertNoPending()
    }

    /** Review M3: a stop the server heard carries the newest position, so an earlier unsent position for the item goes (never sent after it). */
    @Test fun aStopTheServerHeardTakesBackAnEarlierUnsentPositionForTheItem() {
        save(film("a", ud(positionMs = 0)))
        save(film("b", ud(positionMs = 0)))
        gateway.failOps["setPosition"] = AppError.Kind.OFFLINE
        stop("a", 100_000, reached = false)
        stop("b", 50_000, reached = false)
        drain()
        stop("a", 200_000) // heard by the server
        drain()
        assertEquals("only b's row is left", 1, queued())
        assertEquals(200_000, userData("a")!!.positionMs)
        gateway.failOps.clear()
        val from = gateway.calls.size
        sender.kick(s1u1)
        drain()
        assertEquals("a's old position is never sent", listOf("setPosition:${id("b")}:50000"), gateway.calls.drop(from).filter { it.startsWith("setPosition") })
        assertEquals(0, queued())
        assertNoPending()
    }

    @Test fun aStopNotYetToBeSentIsQueuedWithoutAskingForASend() {
        save(film("a", ud(positionMs = 0)))
        runBlocking { playstate.recordStop(s1u1, id("a"), 300_000, finished = false, reached = false, kick = false) }
        drain()
        assertEquals("queued, durable", 1, queued())
        assertTrue("but no send asked for yet: the report goes first", changedFor.isEmpty())
    }

    @Test fun aRefreshAskedBeforeTheStopCannotOverwriteTheNewPosition() = runBlocking {
        save(film("a", ud(positionMs = 60_000)))
        gateway.answerAtRequest = true
        gateway.resumeFor = { listOf(film("a", ud(positionMs = 60_000))) } // as the server was when it was asked
        val l = holdCalls("resume")
        val refreshing = background { repo.refresh(s1u1, RefreshTarget.Home) }
        until { gateway.count("resume") == 1 }
        gateway.resumeFor = { listOf(film("a", ud(positionMs = 900_000))) }
        withTimeout(5_000) { playstate.recordStop(s1u1, id("a"), 900_000, finished = false) }
        l.release()
        withTimeout(5_000) { refreshing.await() }
        settle()
        assertEquals(900_000, userData("a")!!.positionMs)
        assertNoPending()
    }

    @Test fun anItemThisUserWasNeverShownIsNotFoundAndNothingIsWritten() {
        save(film("a", ud(positionMs = 0)), s = s1u2)
        val e = failure { playstate.recordStop(s1u1, id("a"), 5_000, finished = false) }
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, e.kind)
        assertNull(userData("a"))
        assertEquals(0, userData("a", s1u2)!!.positionMs)
        assertEquals(0, gateway.count("resume"))
    }

    @Test fun aMalformedIdIsNotFound() {
        val e = failure { playstate.recordStop(s1u1, "../x", 5_000, finished = false) }
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, e.kind)
    }

    @Test fun aNegativePositionIsSavedAsTheStart() {
        save(film("a", ud(positionMs = 30_000)))
        stop("a", -5)
        drain()
        assertEquals(0, userData("a")!!.positionMs)
    }
}
