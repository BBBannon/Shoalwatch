package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Marking one film or episode watched or unwatched, and favouriting. */
class PlaystatePlayedTest : PlaystateTestBase() {
    private fun played(label: String, value: Boolean) = runBlocking { playstate.setPlayed(s1u1, id(label), value) }

    @Test fun markingAFilmWatchedMatchesWhatTheServerRecords() {
        saveResume(film("a"), film("b", ud(positionMs = 60_000, playCount = 0)))
        gateway.resumeFor = { listOf(film("a")) }
        val started = now
        played("b", true)
        drain()
        val d = userData("b")!!
        assertTrue(d.played)
        assertEquals(0, d.positionMs)
        assertNull(d.playedPercentage)
        assertEquals(1, d.playCount)
        assertTrue(d.lastPlayed!! in started..now)
        assertEquals(ids("a"), resumeIds())
        assertEquals(listOf("setPlayed:${id("b")}:true"), gateway.calls.filter { it.startsWith("setPlayed") })
        assertNoPending()
    }

    @Test fun anExistingHigherPlayCountIsKept() {
        save(film("a", ud(played = true, playCount = 3)))
        played("a", true)
        assertEquals(3, userData("a")!!.playCount)
    }

    @Test fun markingUnwatchedResetsTheCountKeepsTheDateAndLeavesContinueWatching() {
        saveResume(film("a", ud(played = true, positionMs = 4_000, playCount = 2, lastPlayed = 123)), film("b"))
        gateway.resumeFor = { listOf(film("b")) }
        played("a", false)
        drain()
        val d = userData("a")!!
        assertFalse(d.played)
        assertEquals(0, d.positionMs)
        assertEquals(0, d.playCount)
        assertEquals(123L, d.lastPlayed)
        assertEquals(ids("b"), resumeIds())
    }

    @Test fun theChangeIsVisibleBeforeTheServerAnswers() = runBlocking {
        saveResume(film("a"))
        val held = holdServer()
        val call = background { playstate.setPlayed(s1u1, id("a"), true) }
        until { gateway.count("setPlayed") == 1 }
        assertTrue(userData("a")!!.played)
        assertNotNull(userData("a")!!.pendingSyncAt)
        assertEquals(emptyList<String>(), resumeIds())
        call.await()
        release(held)
        settle()
        assertNoPending()
    }

    @Test fun aRefreshLandingWhileTheServerIsAnsweringDoesNotUndoIt() = runBlocking {
        saveResume(film("a"))
        gateway.resumeFor = { listOf(film("a")) } // the server has not heard yet
        gateway.nextUpFor = { listOf(film("a", ud())) }
        val held = holdMutation()
        val call = background { playstate.setPlayed(s1u1, id("a"), true) }
        until { gateway.count("setPlayed") == 1 }
        repo.refresh(s1u1, RefreshTarget.Home)
        assertTrue("the server's old copy must not win", userData("a")!!.played)
        gateway.resumeFor = { emptyList() } // now it has heard
        gateway.nextUpFor = { emptyList() }
        held.release()
        call.await()
        settle()
        assertTrue(userData("a")!!.played)
        assertNoPending()
        assertEquals(emptyList<String>(), resumeIds())
    }

    @Test fun afterSuccessTheServerIsAskedAgainForContinueWatchingAndNextUp() = runBlocking {
        saveResume(film("a"))
        gateway.resumeFor = { emptyList() }
        gateway.nextUpFor = { listOf(row(item("n"), ud())) }
        playstate.setPlayed(s1u1, id("a"), true)
        settle()
        assertEquals(1, gateway.count("resume"))
        assertEquals(1, gateway.count("nextUp"))
        assertEquals(ids("n"), runBlocking { listDao.entryIds(s1u1.serverId, s1u1.userId, ListKey.NEXT_UP) })
    }

    @Test fun aRefreshAlreadyRunningWhenTheServerAnswersCannotBringTheOldStateBack() = runBlocking {
        saveResume(film("a"))
        var answers = 0
        gateway.resumeFor = { if (answers++ == 0) listOf(film("a")) else emptyList() } // the first answer is the old one: the server had not heard
        val held = latch()
        gateway.onCall = { call -> if (call == "resume:24") held.await() }
        val refreshing = background { repo.refresh(s1u1, RefreshTarget.Home) }
        until { gateway.count("resume") == 1 } // its answer is old, and held
        val action = background { playstate.setPlayed(s1u1, id("a"), true) }
        until { gateway.count("setPlayed") == 1 }
        held.release()
        refreshing.await()
        action.await()
        settle()
        assertTrue("the old answer must not have undone it", userData("a")!!.played)
        assertEquals(emptyList<String>(), resumeIds())
        assertNoPending()
    }

    @Test fun offlineTheChangeStaysOnScreenAndIsQueuedNotRolledBack() {
        saveResume(film("a"), film("b", ud(positionMs = 5_000, playCount = 2, lastPlayed = 77)), film("c"))
        gateway.failAll = AppError.Kind.OFFLINE
        played("b", true) // returns normally
        drain()
        assertTrue(userData("b")!!.played)
        assertEquals(ids("a", "c"), resumeIds())
        assertEquals(1, queued())
        assertEquals(1, pendingRows())
        assertEquals("nothing is refreshed until the server has it", 0, gateway.calls.count { it == "resume:24" })
    }

    @Test fun anotherUsersWatchedStateIsUntouched() {
        save(film("a", ud(played = false)))
        save(film("a", ud(played = false, positionMs = 7)), s = s1u2)
        played("a", true)
        assertFalse(userData("a", s1u2)!!.played)
        assertEquals(7, userData("a", s1u2)!!.positionMs)
        assertNull(userData("a", s1u2)!!.pendingSyncAt)
    }

    @Test fun anItemNotShownToThisUserIsNotFoundWithNothingSent() {
        save(film("a"), s = s1u2)
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, failure { playstate.setPlayed(s1u1, id("a"), true) }.kind)
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, failure { playstate.setPlayed(s1u1, "nonsense", true) }.kind)
        assertTrue(gateway.calls.isEmpty())
        assertFalse(userData("a", s1u2)!!.played)
    }

    // ---- favourite

    @Test fun favouritingFlipsOnlyTheFlagAndEditsNoList() {
        saveResume(film("a"))
        saveList("children:x", film("a"))
        runBlocking { playstate.setFavourite(s1u1, id("a"), true) }
        assertTrue(userData("a")!!.favourite)
        assertEquals(ids("a"), resumeIds())
        assertEquals(1, count("list_entries", "listKey = 'children:x'"))
        assertEquals(listOf("setFavourite:${id("a")}:true"), gateway.calls.filter { it.startsWith("setFavourite") })
        assertNoPending()
    }

    @Test fun favouritingAsksTheServerForNoListsAgain() = runBlocking {
        save(film("a"))
        playstate.setFavourite(s1u1, id("a"), true)
        settle()
        assertEquals(listOf("setFavourite:${id("a")}:true"), gateway.calls.toList())
    }

    @Test fun favouriteIsVisibleBeforeTheServerAnswersAndKeptWhenTheSendFails() = runBlocking {
        save(film("a", ud(favourite = false)))
        val held = holdServer()
        gateway.failAll = AppError.Kind.TIMEOUT
        background { playstate.setFavourite(s1u1, id("a"), true) }.await()
        until { gateway.count("setFavourite") == 1 }
        assertTrue(userData("a")!!.favourite)
        assertNotNull(userData("a")!!.pendingSyncAt)
        release(held)
        settle()
        assertTrue(userData("a")!!.favourite)
        assertEquals(1, queued())
    }

    @Test fun unfavouritingWorksAndAnotherUserKeepsTheirFavourite() {
        save(film("a", ud(favourite = true)))
        save(film("a", ud(favourite = true)), s = s1u2)
        runBlocking { playstate.setFavourite(s1u1, id("a"), false) }
        assertFalse(userData("a")!!.favourite)
        assertTrue(userData("a", s1u2)!!.favourite)
    }
}
