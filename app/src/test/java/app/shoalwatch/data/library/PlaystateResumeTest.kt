package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Taking an item off Continue Watching, and the rules every action shares (saved at once, kept and queued when the send fails). */
class PlaystateResumeTest : PlaystateTestBase() {
    private fun remove(label: String, s: Session = s1u1) = runBlocking { playstate.removeFromContinueWatching(s, id(label)) }

    @Test fun aFilmComesOffTheListWithItsPositionClearedAndWatchedUnchanged() {
        saveResume(film("a"), film("b"), film("c"))
        gateway.resumeFor = { listOf(film("a"), film("c")) } // what the server will say after it has heard
        remove("b")
        drain()
        assertEquals(ids("a", "c"), resumeIds())
        val d = userData("b")!!
        assertEquals(0, d.positionMs)
        assertNull(d.playedPercentage)
        assertFalse(d.played)
        assertEquals(listOf("clearResume:${id("b")}"), gateway.calls.filter { it.startsWith("clearResume") })
        assertNoPending()
    }

    @Test fun itDoesNotChangeWatchedAndLeavesNextUpAlone() {
        saveResume(film("a", ud(played = true, positionMs = 5_000)))
        saveList(ListKey.NEXT_UP, film("n", ud()))
        remove("a")
        assertTrue(userData("a")!!.played)
        assertEquals(ids("n"), runBlocking { listDao.entryIds(s1u1.serverId, s1u1.userId, ListKey.NEXT_UP) })
    }

    @Test fun theListShrinksAndTheCountsFollow() {
        saveResume(film("a"), film("b"))
        gateway.failOps["resume"] = AppError.Kind.OFFLINE // so the follow-up refresh leaves the local list as it is
        remove("a")
        drain()
        val state = runBlocking { listDao.getState(s1u1.serverId, s1u1.userId, ListKey.RESUME) }!!
        assertEquals(1, state.loadedCount)
        assertEquals(1, state.total)
    }

    @Test fun theChangeIsVisibleBeforeTheServerAnswers() = runBlocking {
        saveResume(film("a"), film("b"), film("c"))
        val held = holdServer()
        val call = background { playstate.removeFromContinueWatching(s1u1, id("b")) }
        until { gateway.count("clearResume") == 1 }
        val seen = listDao.observeList(s1u1.serverId, s1u1.userId, ListKey.RESUME, 50).first5s()
        assertEquals(listOf("Film a", "Film c"), seen.map { it.item.name })
        assertEquals(0, userData("b")!!.positionMs)
        assertNotNull(userData("b")!!.pendingSyncAt)
        call.await() // saved and queued: the action does not wait for the server
        release(held)
        settle()
        assertNoPending()
    }

    @Test fun aRefreshLandingWhileTheServerIsAnsweringDoesNotBringTheOldPositionBack() = runBlocking {
        saveResume(film("a"), film("b"))
        gateway.resumeFor = { listOf(film("a"), film("b")) } // the server has not heard yet: it still has b at 60s
        val held = holdMutation()
        val call = background { playstate.removeFromContinueWatching(s1u1, id("b")) }
        until { gateway.count("clearResume") == 1 }
        repo.refresh(s1u1, RefreshTarget.Home) // the refresh's own calls pass: only the action's call is held
        assertEquals(0, userData("b")!!.positionMs)
        held.release()
        call.await()
        settle()
        assertNoPending()
    }

    @Test fun afterSuccessTheListsTheServerRecomputesAreAskedAgain() = runBlocking {
        saveResume(film("a"), film("b"))
        gateway.resumeFor = { listOf(film("a")) }
        playstate.removeFromContinueWatching(s1u1, id("b"))
        settle()
        assertEquals(1, gateway.count("resume"))
        assertEquals(0, gateway.count("nextUp"))
        assertEquals(ids("a"), resumeIds())
    }

    @Test fun aFailedSendKeepsTheChangeAndItsRowWhateverTheKind() {
        val kinds = listOf(AppError.Kind.SERVER_ERROR, AppError.Kind.OFFLINE, AppError.Kind.NETWORK, AppError.Kind.TIMEOUT, AppError.Kind.UNAUTHORISED)
        for (kind in kinds) {
            saveResume(film("a"), film("b"), film("c"))
            gateway.failOps["clearResume"] = kind
            remove("b") // returns normally: nothing is thrown to the screen
            drain()
            assertEquals(kind.name, ids("a", "c"), resumeIds())
            assertEquals(0, userData("b")!!.positionMs)
            assertNotNull("kept until sent", userData("b")!!.pendingSyncAt)
            assertEquals(1, queued())
            assertEquals("one send, then the drain stops or defers", 1, gateway.count("clearResume"))
            db.openHelper.writableDatabase.execSQL("DELETE FROM sync_outbox")
            gateway.calls.clear()
        }
    }

    @Test fun anItemWithNoUserDataRowIsKeptOffTheListWhileTheSendFails() {
        saveResume(row(item("a"), null))
        gateway.failOps["clearResume"] = AppError.Kind.NETWORK
        remove("a")
        drain()
        assertEquals(emptyList<String>(), resumeIds())
        assertEquals(1, queued())
    }

    @Test fun anUnexpectedExceptionFromTheServerKeepsTheChangeAndLeaksNothing() {
        saveResume(film("a"))
        gateway.throwOps["clearResume"] = IllegalStateException("https://secret.example/token=abc")
        remove("a")
        drain()
        assertEquals(0, userData("a")!!.positionMs)
        assertEquals(1, queued())
    }

    @Test fun aRefusalForThisItemOverrulesTheChangeAndTheServersCopyComesBack() {
        saveResume(film("a"), film("b"))
        gateway.failOps["clearResume"] = AppError.Kind.FORBIDDEN
        gateway.resumeFor = { listOf(film("a"), film("b")) }
        gateway.itemFor = { film("a") }
        remove("a")
        drain()
        assertEquals(0, queued())
        assertEquals(ids("a", "b"), resumeIds())
        assertEquals(60_000, userData("a")!!.positionMs)
        assertNoPending()
    }

    @Test fun aMalformedIdIsNotFoundWithNothingChangedOrSent() {
        saveResume(film("a"))
        for (bad in listOf("", "not-an-id", "../x", id("a") + "0")) {
            val e = failure { playstate.removeFromContinueWatching(s1u1, bad) }
            assertEquals(AppError.Kind.SERVER_NOT_FOUND, e.kind)
        }
        assertTrue(gateway.calls.isEmpty())
        assertEquals(60_000, userData("a")!!.positionMs)
        assertNoPending()
    }

    @Test fun anItemNotSavedForThisUserIsNotFoundWithNothingSent() {
        save(film("seen"), s = s1u2) // the server showed it to the other user only
        val e = failure { playstate.removeFromContinueWatching(s1u1, id("seen")) }
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, e.kind)
        val unknown = failure { playstate.removeFromContinueWatching(s1u1, id("nobody")) }
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, unknown.kind)
        assertTrue(gateway.calls.isEmpty())
        assertEquals(60_000, userData("seen", s1u2)!!.positionMs)
    }

    @Test fun anotherUsersProgressOnTheSameItemIsUntouched() {
        saveResume(film("a"))
        saveList(ListKey.RESUME, film("a", ud(positionMs = 9_000)), s = s1u2)
        remove("a")
        assertEquals(9_000, userData("a", s1u2)!!.positionMs)
        assertEquals(ids("a"), resumeIds(s1u2))
        assertEquals(0, userData("a")!!.positionMs)
    }
}
