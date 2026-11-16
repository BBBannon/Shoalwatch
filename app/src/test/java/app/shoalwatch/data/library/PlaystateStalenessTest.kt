package app.shoalwatch.data.library

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule: a server answer never overwrites user data (or list membership) for an item changed locally AFTER that
 * answer was asked for. Each test asks first, changes second, and lets the old answer land last.
 */
class PlaystateStalenessTest : PlaystateTestBase() {
    @Test fun aContinueWatchingAnswerAskedForBeforeARemovalDoesNotBringTheItemOrItsPositionBack() = runBlocking {
        saveResume(film("a"), film("b"))
        gateway.answerAtRequest = true
        gateway.resumeFor = { listOf(film("a"), film("b")) } // as the server was when it was asked
        val l = holdCalls("resume")
        val refreshing = background { repo.refresh(s1u1, RefreshTarget.Home) }
        until { gateway.count("resume") == 1 }
        gateway.resumeFor = { listOf(film("b")) } // as the server is once it has heard
        withTimeout(5_000) { playstate.removeFromContinueWatching(s1u1, id("a")) } // completes without waiting for the old answer
        l.release()
        withTimeout(5_000) { refreshing.await() }
        settle()
        assertEquals(ids("b"), resumeIds())
        assertEquals(0, userData("a")!!.positionMs)
        assertNoPending()
    }

    @Test fun anItemAnswerAskedForBeforeAFavouriteDoesNotClearIt() = runBlocking {
        save(film("a", ud(favourite = false)))
        gateway.answerAtRequest = true
        gateway.itemFor = { film("a", ud(favourite = false)) }
        val l = holdCalls("item")
        val refreshing = background { repo.refresh(s1u1, RefreshTarget.Item(id("a"))) }
        until { gateway.count("item") == 1 }
        withTimeout(5_000) { playstate.setFavourite(s1u1, id("a"), true) }
        l.release()
        withTimeout(5_000) { refreshing.await() }
        settle()
        assertTrue(userData("a")!!.favourite)
    }

    @Test fun aListingAnswerAskedForBeforeAPlayedChangeDoesNotRevertIt() = runBlocking {
        save(film("a", ud()))
        val key = ListKey.children(id("lib"), ListShape.DIRECT, nameAsc, noFilter)
        gateway.answerAtRequest = true
        gateway.childPage = { _, start, _ -> Page(listOf(film("a", ud())), start, 1, 1, true) }
        val l = holdCalls("children")
        val refreshing = background { repo.refresh(s1u1, RefreshTarget.Children(id("lib"), ListShape.DIRECT, nameAsc, noFilter)) }
        until { gateway.count("children") == 1 }
        withTimeout(5_000) { playstate.setPlayed(s1u1, id("a"), true) }
        l.release()
        withTimeout(5_000) { refreshing.await() }
        settle()
        assertTrue(userData("a")!!.played)
        assertEquals("the listing itself still arrived", ids("a"), listDao.entryIds(s1u1.serverId, s1u1.userId, key))
    }

    @Test fun aSearchAskedForBeforeAPlayedChangeDoesNotRevertIt() = runBlocking {
        save(film("a", ud()))
        gateway.answerAtRequest = true
        gateway.searchFor = { listOf(film("a", ud())) }
        val l = holdCalls("search")
        val searching = background { repo.search(s1u1, "film") }
        until { gateway.count("search") == 1 }
        withTimeout(5_000) { playstate.setPlayed(s1u1, id("a"), true) }
        l.release()
        withTimeout(5_000) { searching.await() }
        settle()
        assertTrue(userData("a")!!.played)
    }

    @Test fun anAnswerAskedForAfterTheChangeAppliesNormally() = runBlocking {
        save(film("a", ud(favourite = false)))
        playstate.setFavourite(s1u1, id("a"), true)
        settle() // sent and landed
        gateway.itemFor = { film("a", ud(favourite = false)) } // changed on another device since
        repo.refresh(s1u1, RefreshTarget.Item(id("a")))
        assertFalse("the server's value decides once it is asked after the change", userData("a")!!.favourite)
    }

    @Test fun theRuleIsPerUserBobsOldAnswerStillAppliesWhenAnnChangedTheSameItem() = runBlocking {
        save(film("a", ud(favourite = false)))
        save(film("a", ud(favourite = false)), s = s1u2)
        gateway.answerAtRequest = true
        gateway.itemBySession = { s, _ -> film("a", ud(favourite = s == s1u2)) }
        val l = holdCalls("item")
        val refreshing = background { repo.refresh(s1u2, RefreshTarget.Item(id("a"))) }
        until { gateway.count("item") == 1 }
        withTimeout(5_000) { playstate.setPlayed(s1u1, id("a"), true) }
        l.release()
        withTimeout(5_000) { refreshing.await() }
        assertTrue("Bob's answer applies", userData("a", s1u2)!!.favourite)
        assertTrue(userData("a")!!.played)
    }

    @Test fun andAnnsOldAnswerStillAppliesWhenBobChangedTheSameItem() = runBlocking {
        save(film("a", ud(favourite = false)))
        save(film("a", ud(favourite = false)), s = s1u2)
        gateway.answerAtRequest = true
        gateway.itemBySession = { s, _ -> film("a", ud(favourite = s == s1u1)) }
        val l = holdCalls("item")
        val refreshing = background { repo.refresh(s1u1, RefreshTarget.Item(id("a"))) }
        until { gateway.count("item") == 1 }
        withTimeout(5_000) { playstate.setPlayed(s1u2, id("a"), true) }
        l.release()
        withTimeout(5_000) { refreshing.await() }
        assertTrue("Ann's answer applies", userData("a")!!.favourite)
    }

    @Test fun twentyRapidActionsFinishWhileARefreshIsStuck() = runBlocking {
        val labels = (1..20).map { "m$it" }
        save(*labels.map { film(it, ud()) }.toTypedArray(), film("stuck", ud()))
        gateway.itemFor = { film("stuck", ud()) }
        val l = holdCalls("item") // closed for the whole test body
        val refreshing = background { repo.refresh(s1u1, RefreshTarget.Item(id("stuck"))) }
        until { gateway.count("item") == 1 }
        val calls = labels.map { x -> background { playstate.setFavourite(s1u1, id(x), true) } }
        withTimeout(10_000) { calls.forEach { it.await() } }
        assertTrue("the refresh is still stuck", refreshing.isActive)
        labels.forEach { assertTrue(userData(it)!!.favourite) }
        until { pendingRows() == 0 } // every send landed while the refresh is stuck
        l.release()
    }

    // ---- ordering is by sequence, never by the wall clock

    private val hour = 3_600_000L

    @Test fun aWallClockSetBackAnHourAfterAChangeDoesNotMakeLaterAnswersLookOld() = runBlocking {
        gateway.clockTick = {}
        save(film("a", ud(favourite = false)))
        playstate.setFavourite(s1u1, id("a"), true)
        settle() // sent and landed
        now -= hour // the phone's clock is corrected backwards
        gateway.itemFor = { film("a", ud(favourite = false)) } // changed on another device since
        repo.refresh(s1u1, RefreshTarget.Item(id("a")))
        assertFalse("an answer asked for after the change applies, whatever the clock says", userData("a")!!.favourite)
    }

    @Test fun aWallClockSetForwardAnHourWhileAStaleRequestIsInFlightDoesNotLetItThrough() = runBlocking {
        save(film("a", ud(favourite = false)))
        gateway.answerAtRequest = true
        gateway.itemFor = { film("a", ud(favourite = false)) }
        val l = holdCalls("item")
        val refreshing = background { repo.refresh(s1u1, RefreshTarget.Item(id("a"))) }
        until { gateway.count("item") == 1 }
        withTimeout(5_000) { playstate.setFavourite(s1u1, id("a"), true) }
        now += hour
        l.release()
        withTimeout(5_000) { refreshing.await() }
        settle()
        assertTrue(userData("a")!!.favourite)
    }

    @Test fun aRequestStampedStraightAfterAChangeAppliesEvenWithNoTimePassing() = runBlocking {
        gateway.clockTick = {}
        save(film("a", ud(favourite = false)))
        playstate.setFavourite(s1u1, id("a"), true)
        settle() // sent and landed
        gateway.itemFor = { film("a", ud(favourite = false)) }
        repo.refresh(s1u1, RefreshTarget.Item(id("a")))
        assertFalse(userData("a")!!.favourite)
    }

    @Test fun aRequestStampedStraightBeforeAChangeIsDroppedEvenWithNoTimePassing() = runBlocking {
        gateway.clockTick = {}
        save(film("a", ud(favourite = false)))
        gateway.answerAtRequest = true
        gateway.itemFor = { film("a", ud(favourite = false)) }
        val l = holdCalls("item")
        val refreshing = background { repo.refresh(s1u1, RefreshTarget.Item(id("a"))) }
        until { gateway.count("item") == 1 }
        withTimeout(5_000) { playstate.setFavourite(s1u1, id("a"), true) }
        l.release()
        withTimeout(5_000) { refreshing.await() }
        assertTrue(userData("a")!!.favourite)
    }

    // ---- the acknowledgement is a change too

    @Test fun anItemRequestMadeWhileTheServerCallIsInFlightAndAnsweredFromOldStateCannotUndoTheChangeAfterItIsConfirmed() = runBlocking {
        save(film("a", ud(favourite = false)))
        gateway.answerAtRequest = true
        gateway.itemFor = { film("a", ud(favourite = false)) } // the server has not heard yet
        val server = latch()
        val item = latch()
        gateway.onCall = { call -> if (call.startsWith("setFavourite")) server.await() else if (call.startsWith("item")) item.await() }
        val action = background { playstate.setFavourite(s1u1, id("a"), true) }
        until { gateway.count("setFavourite") == 1 }
        val refreshing = background { repo.refresh(s1u1, RefreshTarget.Item(id("a"))) } // asked for after the optimistic write, before the confirmation
        until { gateway.count("item") == 1 }
        server.release()
        withTimeout(5_000) { action.await() }
        item.release() // the old answer lands after the action has completed
        withTimeout(5_000) { refreshing.await() }
        settle()
        assertTrue(userData("a")!!.favourite)
        assertNoPending()
    }

    @Test fun theRefreshAfterTheActionStillAppliesAndSoDoesOneAskedForLater() = runBlocking {
        saveResume(film("a"), film("b"))
        gateway.resumeFor = { listOf(film("b")) }
        playstate.removeFromContinueWatching(s1u1, id("a"))
        settle()
        assertEquals(ids("b"), resumeIds())
        gateway.resumeFor = { listOf(film("b"), film("c")) }
        repo.refresh(s1u1, RefreshTarget.Home)
        assertEquals(ids("b", "c"), resumeIds())
    }
}
