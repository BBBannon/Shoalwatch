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

    @Test fun twoRapidActionsOnOneItemRunOneAfterTheOther() = runBlocking {
        save(film("a", ud()))
        val held = holdServer()
        val first = background { playstate.setPlayed(s1u1, id("a"), true) }
        until { gateway.count("setPlayed") == 1 }
        val second = background { playstate.setPlayed(s1u1, id("a"), false) }
        delay(150) // a negative check: long enough for an unserialised second action to have called the server
        assertEquals(1, gateway.count("setPlayed"))
        assertTrue(userData("a")!!.played)
        release(held)
        first.within5s()
        second.within5s()
        settle()
        assertEquals(listOf("setPlayed:${id("a")}:true", "setPlayed:${id("a")}:false"), gateway.calls.filter { it.startsWith("setPlayed") })
        assertEquals(1, gateway.maxRunning)
        assertFalse(userData("a")!!.played)
        assertNoPending()
    }

    @Test fun aSecondActionStartsFromTheFirstsFinishedStateEvenWhenTheFirstFails() = runBlocking {
        save(film("a", ud(favourite = false)))
        val held = holdServer()
        var calls = 0
        gateway.onCall = { if (++calls == 1) gateway.failOps["setFavourite"] = AppError.Kind.NETWORK else gateway.failOps.clear() } // the first call fails, the second succeeds
        val first = background { runCatching { playstate.setFavourite(s1u1, id("a"), true) } }
        until { gateway.count("setFavourite") == 1 }
        val second = background { runCatching { playstate.setFavourite(s1u1, id("a"), true) } }
        delay(100)
        release(held)
        assertEquals(AppError.Kind.NETWORK, (first.within5s().exceptionOrNull() as AppError).kind)
        assertTrue(second.within5s().isSuccess)
        assertTrue(userData("a")!!.favourite)
        assertNoPending()
    }

    @Test fun actionsOnDifferentItemsOfOneUserAlsoWaitTheirTurn() = runBlocking {
        save(film("a", ud()), film("b", ud()))
        val held = holdServer()
        val first = background { playstate.setFavourite(s1u1, id("a"), true) }
        until { gateway.count("setFavourite") == 1 }
        val second = background { playstate.setFavourite(s1u1, id("b"), true) }
        delay(150) // a negative check: long enough for an unqueued second action to have changed its row
        assertFalse(userData("b")!!.favourite)
        assertEquals(1, gateway.count("setFavourite"))
        release(held)
        first.within5s()
        second.within5s()
        assertTrue(userData("b")!!.favourite)
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

    @Test fun aCallerThatLeavesBeforeAFailureStillGetsTheRollback() = runBlocking {
        saveResume(film("a"), film("b"))
        val held = holdServer()
        gateway.failAll = AppError.Kind.OFFLINE
        val call = background { playstate.removeFromContinueWatching(s1u1, id("a")) }
        until { gateway.count("clearResume") == 1 }
        call.cancelAndJoin()
        release(held)
        settle()
        assertEquals(ids("a", "b"), resumeIds())
        assertEquals(60_000, userData("a")!!.positionMs)
        assertNoPending()
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

    @Test fun aFailureToRestoreStillClearsEveryMarkerAndRefreshesTheLists() = runBlocking {
        saveResume(film("a"), film("b"))
        gateway.failAll = AppError.Kind.SERVER_ERROR
        gateway.onCall = { call -> if (call.startsWith("clearResume")) sql("DROP TABLE list_entries") } // the restore cannot put the entry back
        val e = failure { playstate.removeFromContinueWatching(s1u1, id("a")) }
        settle()
        assertEquals("the original error, not the restore's", AppError.Kind.SERVER_ERROR, e.kind)
        assertNoPending()
        assertTrue("a forced refresh to reconverge with the server", gateway.count("resume") >= 1)
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
        val dead = PlaystateRepository(gateway, repo, CoroutineScope(Job().apply { cancel() }), Dispatchers.IO, { now })
        try {
            withTimeout(5_000) { dead.removeFromContinueWatching(s1u1, id("a")) }
            fail("expected a cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals(60_000, userData("a")!!.positionMs)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test fun theAppScopeShuttingDownMidCallStillPutsTheRowsBack() = runBlocking {
        saveResume(film("a"), film("b"))
        val own = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
        val mine = PlaystateRepository(gateway, repo, own, Dispatchers.IO, { now })
        holdServer()
        val call = background { runCatching { mine.removeFromContinueWatching(s1u1, id("a")) } }
        until { gateway.count("clearResume") == 1 }
        own.coroutineContext[Job]!!.cancelAndJoin()
        assertTrue(call.within5s().exceptionOrNull() is CancellationException)
        assertEquals(ids("a", "b"), resumeIds())
        assertEquals(60_000, userData("a")!!.positionMs)
        assertNoPending()
    }

    @Test fun theChangeAndItsUndoWaitForTheLibraryWriteGate() = runBlocking {
        val gate = Mutex()
        val gated = PlaystateRepository(gateway, LibraryRepository(gateway, db, appScope, Dispatchers.IO, { now }, log, gate), appScope, Dispatchers.IO, { now })
        saveResume(film("a"))
        val held = holdMutation()
        gateway.failAll = AppError.Kind.NETWORK
        gate.withLock {
            val call = background { runCatching { gated.removeFromContinueWatching(s1u1, id("a")) } }
            delay(150) // a negative check: long enough for an ungated write to have happened
            assertEquals(60_000, userData("a")!!.positionMs)
            assertTrue(gateway.calls.isEmpty())
            gate.unlock()
            until { gateway.count("clearResume") == 1 } // changed, and now at the server
            assertEquals(0, userData("a")!!.positionMs)
            gate.lock() // hold it again: the rollback must wait here too
            held.release()
            delay(150)
            assertEquals("not restored while the gate is held", 0, userData("a")!!.positionMs)
            gate.unlock()
            assertEquals(AppError.Kind.NETWORK, (call.within5s().exceptionOrNull() as AppError).kind)
            gate.lock()
        }
        assertEquals(60_000, userData("a")!!.positionMs)
        assertEquals(ids("a"), resumeIds())
        assertNoPending()
    }

    @Test fun aFavouriteIsSetForTheSessionsUserOnly() = runBlocking {
        save(film("a", ud()), s = s1u1)
        save(film("a", ud()), s = s2u1) // same user id on another server
        playstate.setFavourite(s1u1, id("a"), true)
        assertNull(userData("a", s2u1)?.takeIf { it.favourite })
    }
}
