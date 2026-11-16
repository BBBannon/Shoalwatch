package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where playback stopped, saved on this phone: queued only when the stop report did not reach the server. */
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

    @Test fun aFinishedStopTheServerHeardIsWatchedHereWithNothingQueued() {
        save(film("a", ud(positionMs = 60_000)))
        saveResume(film("a", ud(positionMs = 60_000)))
        stop("a", 7_000_000, finished = true)
        drain()
        assertEquals("the stop report did it", 0, gateway.count("setPlayed"))
        assertEquals(0, queued())
        assertTrue(userData("a")!!.played)
        assertEquals(0, userData("a")!!.positionMs)
        assertEquals(emptyList<String>(), resumeIds())
        assertTrue(gateway.count("resume") >= 1)
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

    @Test fun aStopTheServerHeardDoesNotClearTheMarkerOfAnEarlierUnsentChange() {
        save(film("a", ud(positionMs = 0)))
        gateway.failOps["setPosition"] = AppError.Kind.OFFLINE
        stop("a", 100_000, reached = false)
        drain()
        stop("a", 200_000) // heard by the server, but the row before it is still waiting
        drain()
        assertEquals(1, queued())
        assertEquals(1, pendingRows())
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
