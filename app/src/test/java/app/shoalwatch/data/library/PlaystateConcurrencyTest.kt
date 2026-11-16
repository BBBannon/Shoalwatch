package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Serialising per item, finishing what was started, never leaving a marker behind, and going through the write gate. */
class PlaystateConcurrencyTest : PlaystateTestBase() {
    private suspend fun <T> kotlinx.coroutines.Deferred<T>.within5s() = withTimeout(5_000) { await() }

    @Test fun aSecondTapOnOneItemShowsAtOnceAndTheSendsGoInOrderOneAtATime() = runBlocking {
        save(film("a", ud()))
        val held = holdServer()
        background { playstate.setPlayed(s1u1, id("a"), true) }.within5s()
        until { gateway.count("setPlayed") == 1 } // the first send is at the server
        background { playstate.setPlayed(s1u1, id("a"), false) }.within5s()
        assertFalse("the second tap shows without waiting for the first's send", userData("a")!!.played)
        assertEquals(1, gateway.count("setPlayed"))
        release(held)
        settle()
        assertEquals(listOf("setPlayed:${id("a")}:true", "setPlayed:${id("a")}:false"), gateway.calls.filter { it.startsWith("setPlayed") })
        assertEquals("two sends were at the server at once", 1, gateway.maxMutationsRunning)
        assertFalse(userData("a")!!.played)
        assertEquals(0, queued())
        assertNoPending()
    }

    @Test fun aFailedSendKeepsTheChangeAndALaterTapOnTheSameItemReplacesItsRow() = runBlocking {
        save(film("a", ud(favourite = false)))
        gateway.failOps["setFavourite"] = AppError.Kind.NETWORK
        playstate.setFavourite(s1u1, id("a"), true)
        settle()
        assertTrue(userData("a")!!.favourite)
        assertEquals(1, queued())
        playstate.setFavourite(s1u1, id("a"), false)
        settle()
        assertEquals("one row per item and kind", 1, queued())
        gateway.failOps.clear()
        sender.kick(s1u1)
        settle()
        val sends = gateway.calls.filter { it.startsWith("setFavourite") }
        assertEquals("the first tap's one failed send", 1, sends.count { it.endsWith(":true") })
        assertTrue("the replacing row went last", sends.last().endsWith(":false"))
        assertFalse(userData("a")!!.favourite)
        assertNoPending()
    }

    @Test fun actionsOnDifferentItemsOfOneUserDoNotWaitForEachOthersSends() = runBlocking {
        save(film("a", ud()), film("b", ud()))
        val held = holdServer()
        background { playstate.setFavourite(s1u1, id("a"), true) }.within5s()
        until { gateway.count("setFavourite") == 1 }
        background { playstate.setFavourite(s1u1, id("b"), true) }.within5s()
        assertTrue(userData("b")!!.favourite)
        assertEquals("b's send waits its turn behind a's", 1, gateway.count("setFavourite"))
        release(held)
        settle()
        assertEquals(listOf(id("a"), id("b")), gateway.calls.filter { it.startsWith("setFavourite") }.map { it.split(':')[1] })
        assertNoPending()
    }

    @Test fun aCallerThatLeavesAfterTheChangeDoesNotStopTheActionFinishing() = runBlocking {
        saveResume(film("a"), film("b"))
        gateway.resumeFor = { listOf(film("b")) }
        val held = holdServer()
        val call = background { playstate.removeFromContinueWatching(s1u1, id("a")) }
        until { gateway.count("clearResume") == 1 }
        call.cancelAndJoin() // the screen is gone
        assertNotNull("still waiting for the server", userData("a")!!.pendingSyncAt)
        release(held)
        settle()
        assertNoPending()
        assertEquals(1, gateway.count("clearResume"))
        assertEquals("the lists were refreshed", 1, gateway.count("resume"))
        assertEquals(ids("b"), resumeIds())
    }

    @Test fun aCallerThatLeavesStraightAwayStillHasItsChangeKeptWhenTheSendFails() = runBlocking {
        saveResume(film("a"), film("b"))
        gateway.failOps["clearResume"] = AppError.Kind.OFFLINE
        val call = background { playstate.removeFromContinueWatching(s1u1, id("a")) }
        call.cancelAndJoin()
        settle()
        assertEquals(ids("b"), resumeIds())
        assertEquals(0, userData("a")!!.positionMs)
        assertEquals(1, queued())
    }

    @Test fun aCallerThatLeavesWhileQueuedBehindAnotherActionStillHasItRun() = runBlocking {
        save(film("a", ud()))
        val held = holdServer()
        val first = background { playstate.setFavourite(s1u1, id("a"), true) }
        until { gateway.count("setFavourite") == 1 }
        val second = background { playstate.setPlayed(s1u1, id("a"), true) }
        delay(100) // queued behind the first
        second.cancelAndJoin()
        release(held)
        first.within5s()
        settle()
        assertTrue("the user's tap is honoured", userData("a")!!.played)
        assertNoPending()
    }

    @Test fun theMarkerIsClearedEvenIfTheFollowUpRefreshFails() = runBlocking {
        saveResume(film("a"))
        gateway.failOps["resume"] = AppError.Kind.OFFLINE
        playstate.removeFromContinueWatching(s1u1, id("a"))
        settle()
        assertNoPending()
    }

    @Test fun anActionOnADeadAppScopeEndsInsteadOfHanging() = runBlocking {
        saveResume(film("a"))
        val dead = PlaystateRepository(repo, outbox, db.offlinePlayDao(), { }, CoroutineScope(Job().apply { cancel() }), Dispatchers.IO, { now })
        try {
            withTimeout(5_000) { dead.removeFromContinueWatching(s1u1, id("a")) }
            fail("expected a cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals(60_000, userData("a")!!.positionMs)
        assertEquals(0, queued())
        assertTrue(gateway.calls.isEmpty())
    }

    @Test fun theAppScopeEndingAfterTheChangeKeepsTheChangeAndItsRowForTheNextStart() = runBlocking {
        saveResume(film("a"), film("b"))
        val own = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        val mine = PlaystateRepository(repo, outbox, db.offlinePlayDao(), { }, own, Dispatchers.IO, { now }) // nothing sends it in this "process"
        mine.removeFromContinueWatching(s1u1, id("a"))
        own.coroutineContext[Job]!!.cancelAndJoin()
        assertEquals(ids("b"), resumeIds())
        assertEquals(0, userData("a")!!.positionMs)
        assertNotNull(userData("a")!!.pendingSyncAt)
        assertEquals(1, queued())
    }

    @Test fun theChangeAndItsLandingWaitForTheLibraryWriteGate() = runBlocking {
        val gate = Mutex()
        val gatedLibrary = LibraryRepository(gateway, db, appScope, Dispatchers.IO, { now }, log, changes, gate)
        val gatedSender = app.shoalwatch.data.sync.SyncSender(outbox, sync, PlaystateLanding(gatedLibrary, outbox, db.offlinePlayDao()) { now }, { true }, {}, appScope, { now }, Dispatchers.IO)
        val gated = PlaystateRepository(gatedLibrary, outbox, db.offlinePlayDao(), { s -> gatedSender.kick(s) }, appScope, Dispatchers.IO, { now })
        saveResume(film("a"))
        gateway.resumeFor = { emptyList() }
        val held = holdMutation()
        gate.withLock {
            val call = background { gated.removeFromContinueWatching(s1u1, id("a")) }
            delay(150) // a negative check: long enough for an ungated write to have happened
            assertEquals(60_000, userData("a")!!.positionMs)
            assertEquals(0, queued())
            gate.unlock()
            call.within5s()
            until { gateway.count("clearResume") == 1 } // changed, queued, and at the server
            gate.lock() // the landing must wait here too
            held.release()
            delay(150)
            assertNotNull("not landed while the gate is held", userData("a")!!.pendingSyncAt)
            assertEquals(1, queued())
            gate.unlock()
            until { queued() == 0 }
            gate.lock()
        }
        settle()
        assertEquals(0, userData("a")!!.positionMs)
        assertNoPending()
    }

    @Test fun aFavouriteIsSetForTheSessionsUserOnly() = runBlocking {
        save(film("a", ud()), s = s1u1)
        save(film("a", ud()), s = s2u1) // same user id on another server
        playstate.setFavourite(s1u1, id("a"), true)
        assertNull(userData("a", s2u1)?.takeIf { it.favourite })
    }

    /** The diagnosis of a flaky run: the first action's follow-up refresh can be at the gateway while the second action's call is, so "at most one call at once" was the wrong thing to assert. */
    @Test fun aRefreshOverlappingASecondActionsCallIsNotTwoMutationsAtOnce() = runBlocking {
        save(film("a", ud()), film("b", ud()))
        val resume = latch()
        gateway.onCall = { call -> if (call.startsWith("resume")) resume.await() }
        withTimeout(5_000) { playstate.setPlayed(s1u1, id("a"), true) }
        until { gateway.count("resume") == 1 } // the follow-up refresh is now held at the gateway
        withTimeout(5_000) { playstate.setPlayed(s1u1, id("b"), true) }
        assertTrue("a refresh and a mutation were at the gateway together", gateway.maxRunning >= 2)
        assertEquals(1, gateway.maxMutationsRunning)
        resume.release()
    }
}
