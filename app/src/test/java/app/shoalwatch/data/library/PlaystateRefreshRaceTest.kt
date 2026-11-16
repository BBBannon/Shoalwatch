package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A refresh of ANY list that began before the server heard cannot revert a change after its marker is cleared; and a failed clear does not fail the action. */
class PlaystateRefreshRaceTest : PlaystateTestBase() {
    @Test fun anItemRefreshInFlightWithOldDataCannotRevertAFavourite() = runBlocking {
        save(film("a", ud(favourite = false)))
        gateway.itemFor = { film("a", ud(favourite = false)) } // the detail answer the server gave before it heard
        val l = holdCalls("item")
        val refreshing = background { repo.refresh(s1u1, RefreshTarget.Item(id("a"))) }
        until { gateway.count("item") == 1 }
        val call = background { playstate.setFavourite(s1u1, id("a"), true) }
        until { gateway.count("setFavourite") == 1 }
        l.release()
        withTimeout(5_000) { refreshing.await(); call.await() }
        settle()
        assertTrue("the old detail answer must not have undone the favourite", userData("a")!!.favourite)
        assertNoPending()
    }

    @Test fun aListingRefreshInFlightWithOldDataCannotRevertAPlayedChange() = runBlocking {
        val parent = id("lib")
        save(film("a", ud()))
        gateway.childPage = { _, start, _ -> Page(listOf(film("a", ud())), start, 1, 1, true) }
        val l = holdCalls("children")
        val refreshing = background { repo.refresh(s1u1, RefreshTarget.Children(parent, ListShape.DIRECT, nameAsc, noFilter)) }
        until { gateway.count("children") == 1 }
        val call = background { playstate.setPlayed(s1u1, id("a"), true) }
        until { gateway.count("setPlayed") == 1 }
        l.release()
        withTimeout(5_000) { refreshing.await(); call.await() }
        settle()
        assertTrue(userData("a")!!.played)
        assertNoPending()
    }

    // ---- M1: the server accepted the change, so a failed clear is not the caller's failure

    private fun failClearingMarkerOf(label: String) = sql(
        "CREATE TRIGGER stuck BEFORE UPDATE ON user_data WHEN OLD.itemId = '${id(label)}' AND OLD.pendingSyncAt IS NOT NULL AND NEW.pendingSyncAt IS NULL " +
            "BEGIN SELECT RAISE(ABORT, 'no'); END",
    )

    @Test fun aLandingThatCannotBeWrittenKeepsTheRowAndTheNextDrainLandsIt() = runBlocking {
        saveResume(film("a"), film("b"))
        gateway.resumeFor = { listOf(film("b")) }
        failClearingMarkerOf("a")
        playstate.removeFromContinueWatching(s1u1, id("a")) // returns normally
        settle()
        assertEquals("the landing rolled back: the row stays", 1, queued())
        assertEquals(1, pendingRows())
        assertEquals("no refresh before it has landed", 0, gateway.count("resume"))
        sql("DROP TRIGGER stuck")
        sender.kick(s1u1)
        settle()
        assertEquals(0, queued())
        assertNoPending()
        assertEquals(1, gateway.count("resume"))
    }

    @Test fun aMarkerLeftByAFailedClearIsClearedOnceTheQueueIsIdleAgain() = runBlocking {
        saveResume(film("a"))
        save(film("b", ud()))
        failClearingMarkerOf("a")
        playstate.removeFromContinueWatching(s1u1, id("a"))
        assertEquals("stuck for now", 1, pendingRows())
        sql("DROP TRIGGER stuck")
        playstate.setFavourite(s1u1, id("b"), true) // the queue is busy and then idle again
        settle()
        assertNoPending()
    }

    @Test fun aLandingIsAllOrNothing() = runBlocking {
        save(series(), season("s1", 1), ep("e1", "s1", 1), ep("e2", "s1", 2))
        saveList(ListKey.seasons(id("show")), season("s1", 1))
        failClearingMarkerOf("e2")
        playstate.setPlayed(s1u1, id("s1"), true)
        settle()
        assertEquals("every marker stays with its row", 3, pendingRows())
        assertEquals(1, queued())
        assertTrue(userData("e1")!!.played)
    }
}
