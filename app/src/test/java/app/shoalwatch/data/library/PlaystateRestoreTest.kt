package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Putting things back exactly: the order of list entries, badges shared by sibling episodes, and an item revoked mid-action. */
class PlaystateRestoreTest : PlaystateTestBase() {
    /** An episode of [season] (a real season number, so episodes order by season first). */
    private fun epIn(label: String, seasonLabel: String, season: Int, number: Int) =
        row(item(label, ItemKind.EPISODE, "Episode $label", seriesId = "show", seasonId = seasonLabel, indexNumber = number, parentIndexNumber = season), ud(positionMs = 1_000))

    // ---- I4: entries go back in the order they were

    @Test fun aFailedSeriesMarkPutsContinueWatchingBackInItsOriginalOrder() {
        val e1 = epIn("e1", "s1", 1, 1)
        val e2 = epIn("e2", "s1", 1, 2)
        val e3 = epIn("e3", "s2", 2, 1)
        save(series(), season("s1", 1), season("s2", 2), e1, e2, e3)
        saveList(ListKey.seasons(id("show")), season("s1", 1), season("s2", 2))
        saveResume(film("film"), e1, film("x"), e2, e3, film("y"))
        val before = resumeIds()
        gateway.failAll = AppError.Kind.OFFLINE
        failure { playstate.setPlayed(s1u1, id("show"), true) }
        assertEquals(before, resumeIds())
    }

    @Test fun aFailedSeasonMarkPutsAdjacentEntriesBackInOrder() {
        val e1 = epIn("e1", "s1", 1, 1)
        val e2 = epIn("e2", "s1", 1, 2)
        save(series(), season("s1", 1), e1, e2)
        saveResume(e1, e2, film("x"))
        val before = resumeIds()
        gateway.failAll = AppError.Kind.NETWORK
        failure { playstate.setPlayed(s1u1, id("s1"), true) }
        assertEquals(before, resumeIds())
    }

    // ---- the two other branches of the undo

    @Test fun anEntryAlreadyBackInTheListByRestoreTimeIsNotInsertedTwice() = runBlocking {
        saveResume(film("a"), film("b"))
        val held = holdMutation()
        gateway.failOps["clearResume"] = AppError.Kind.SERVER_ERROR
        gateway.resumeFor = { listOf(film("a"), film("b")) } // the server still lists it, and this is asked for after the removal
        val call = background { runCatching { playstate.removeFromContinueWatching(s1u1, id("a")) } }
        until { gateway.count("clearResume") == 1 }
        repo.refresh(s1u1, RefreshTarget.Home)
        assertEquals("a refresh asked for after the removal puts it back", ids("a", "b"), resumeIds())
        held.release()
        withTimeout(5_000) { call.await() }
        settle()
        assertEquals(ids("a", "b"), resumeIds())
    }

    @Test fun aRecordedPositionBeyondTheCurrentListIsClamped() = runBlocking {
        saveResume(film("x"), film("y"), film("z"), film("a"))
        val held = holdMutation()
        gateway.failOps["clearResume"] = AppError.Kind.SERVER_ERROR
        gateway.resumeFor = { listOf(film("x")) } // the server's list has shrunk meanwhile
        val call = background { runCatching { playstate.removeFromContinueWatching(s1u1, id("a")) } }
        until { gateway.count("clearResume") == 1 }
        repo.refresh(s1u1, RefreshTarget.Home)
        assertEquals(ids("x"), resumeIds())
        held.release()
        withTimeout(5_000) { call.await() }
        settle()
        assertEquals("position 3 does not exist any more: it goes to the end", ids("x", "a"), resumeIds())
    }

    // ---- I3: sibling episodes share the season and series rows

    private fun sibling(failing: String, other: String): Pair<Boolean, Boolean> = runBlocking {
        save(series(), season("s1", 1), ep("e1", "s1", 1), ep("e2", "s1", 2))
        val hold = latch()
        gateway.onCall = { call ->
            if (call.contains(id(failing))) { hold.await(); gateway.failOps["setPlayed"] = AppError.Kind.TIMEOUT } else gateway.failOps.clear()
        }
        val bad = background { runCatching { playstate.setPlayed(s1u1, id(failing), true) } }
        until { gateway.count("setPlayed") == 1 }
        val good = background { runCatching { playstate.setPlayed(s1u1, id(other), true) } }
        delay(200) // whether the second waits (it should) or not, the first is still at the server
        hold.release()
        withTimeout(5_000) { bad.await(); good.await() }
        settle()
        userData(failing)!!.played to userData(other)!!.played
    }

    private fun assertBadgesExact(watchedEpisodes: Int) {
        assertEquals(3 - watchedEpisodes, userData("s1")!!.unplayedItemCount)
        assertEquals(10 - watchedEpisodes, userData("show")!!.unplayedItemCount)
        assertNoPending()
    }

    @Test fun oneSiblingSucceedsAndTheOtherFailsAfterwardsLeavesExactBadgesAndNoMarker() {
        val (failedWatched, okWatched) = sibling(failing = "e2", other = "e1")
        assertFalse(failedWatched)
        assertTrue(okWatched)
        assertBadgesExact(1)
    }

    @Test fun theSameWhenTheFirstTappedIsTheOneThatSucceeds() {
        val (failedWatched, okWatched) = sibling(failing = "e1", other = "e2")
        assertFalse(failedWatched)
        assertTrue(okWatched)
        assertBadgesExact(1)
    }

    @Test fun twentyRapidActionsRunInOrderAndLeaveNoMarker() = runBlocking {
        val labels = (1..20).map { "m$it" }
        save(*labels.map { film(it, ud()) }.toTypedArray())
        val held = holdServer()
        val calls = labels.map { l -> background { playstate.setFavourite(s1u1, id(l), true) } }
        until { gateway.count("setFavourite") == 1 }
        release(held)
        withTimeout(10_000) { calls.forEach { it.await() } }
        settle()
        assertEquals(labels.map { "setFavourite:${id(it)}:true" }, gateway.calls.filter { it.startsWith("setFavourite") })
        assertEquals(1, gateway.maxMutationsRunning)
        assertNoPending()
        labels.forEach { assertTrue(userData(it)!!.favourite) }
    }

    @Test fun anotherUsersActionDoesNotWaitForThisOne() = runBlocking {
        save(film("a", ud()))
        save(film("a", ud()), s = s1u2)
        val slow = latch()
        gateway.onCall = { slow.await() }
        val first = background { playstate.setFavourite(s1u1, id("a"), true) }
        until { gateway.count("setFavourite") == 1 }
        val second = background { playstate.setFavourite(s1u2, id("a"), true) }
        until { gateway.count("setFavourite") == 2 } // reaches the server while the first is still held
        slow.release()
        withTimeout(5_000) { first.await(); second.await() }
        assertNoPending()
    }

    // ---- M5: an item the server refuses for this user mid-action

    @Test fun anItemRevokedMidActionIsNotBroughtBackIntoTheListByTheRollback() = runBlocking {
        saveResume(film("a"), film("b"))
        val held = holdMutation()
        gateway.failOps["clearResume"] = AppError.Kind.SERVER_ERROR
        gateway.failOps["item"] = AppError.Kind.SERVER_NOT_FOUND // the server no longer shows this user the item
        val call = background { runCatching { playstate.removeFromContinueWatching(s1u1, id("a")) } }
        until { gateway.count("clearResume") == 1 }
        repo.refresh(s1u1, RefreshTarget.Item(id("a"))) // revokes it for this user
        held.release()
        val e = withTimeout(5_000) { call.await() }.exceptionOrNull() as AppError
        settle()
        assertEquals(AppError.Kind.SERVER_ERROR, e.kind)
        assertEquals("the entry stays gone for a user who may no longer see it", ids("b"), resumeIds())
        assertNoPending()
    }

    @Test fun anItemRevokedMidActionStillClearsItsMarkerOnSuccess() = runBlocking {
        saveResume(film("a"), film("b"))
        val held = holdMutation()
        gateway.failOps["item"] = AppError.Kind.FORBIDDEN
        val call = background { playstate.removeFromContinueWatching(s1u1, id("a")) }
        until { gateway.count("clearResume") == 1 }
        repo.refresh(s1u1, RefreshTarget.Item(id("a")))
        held.release()
        withTimeout(5_000) { call.await() }
        settle()
        assertNoPending()
    }
}
