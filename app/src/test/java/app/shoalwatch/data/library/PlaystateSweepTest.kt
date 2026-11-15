package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A marker with no live action (the app died mid-action) is cleared once, at the first use of the library in a process,
 * before any refresh or action; the server's copy then decides. Milestone 5 owns recovery: it will send the change instead.
 */
class PlaystateSweepTest : PlaystateTestBase() {
    /** What a process killed between the optimistic write and the server's answer leaves behind. */
    private fun leaveMarker(label: String, s: app.shoalwatch.data.servers.Session = s1u1, favourite: Boolean = true) = runBlocking {
        save(film(label, ud(favourite = false)), s = s)
        itemDao.setFavourite(s.serverId, s.userId, id(label), favourite, now, pending = true)
    }

    @Test fun aMarkerLeftByADeadProcessIsClearedAndTheServersValueThenApplies() = runBlocking {
        leaveMarker("a")
        assertNotNull(userData("a")!!.pendingSyncAt)
        gateway.itemFor = { film("a", ud(favourite = false)) } // what the server holds
        repo.refresh(s1u1, RefreshTarget.Item(id("a")))
        assertNull(userData("a")!!.pendingSyncAt)
        assertFalse("the server's value decides", userData("a")!!.favourite)
    }

    @Test fun everyUserAndServerIsSweptNotJustTheOneThatAsked() = runBlocking {
        leaveMarker("a")
        leaveMarker("b", s1u2)
        leaveMarker("c", s2u1)
        repo.refresh(s1u1, RefreshTarget.Item(id("a")))
        assertNoPending()
    }

    @Test fun theListsOfTheUsersWhoHadMarkersBecomeStaleSoTheyRefreshPromptly() = runBlocking {
        saveResume(film("a"))
        leaveMarker("m")
        gateway.resumeFor = { listOf(film("a")) }
        repo.home(s1u1).first5s { it.resume.freshness == Freshness.Fresh && gateway.count("resume") >= 1 }
        assertEquals("a fresh list would not have been asked for again", 1, gateway.count("resume"))
    }

    @Test fun withNothingPendingTheSweepChangesNothing() = runBlocking {
        saveResume(film("a"))
        gateway.resumeFor = { listOf(film("a")) }
        repo.home(s1u1).first5s { it.resume.freshness == Freshness.Fresh }
        settle()
        val first = gateway.count("resume")
        val other = PlaystateRepository(gateway, repo, appScope, Dispatchers.IO, { now })
        other.setFavourite(s1u1, id("a"), true)
        assertNoPending()
        assertEquals(first, gateway.count("resume"))
    }

    @Test fun itRunsOncePerProcess() = runBlocking {
        save(film("a", ud()))
        repo.refresh(s1u1, RefreshTarget.Item(id("a"))) // the first use: the sweep happens
        leaveMarker("b", s1u2) // appears after the sweep: not swept again (a user whose queue goes idle clears only their own)
        repo.refresh(s1u1, RefreshTarget.Item(id("a")))
        playstate.setFavourite(s1u1, id("a"), true)
        assertEquals(1, pendingRows())
    }

    @Test fun anActionCannotStartItsChangeBeforeTheSweepHasRun() = runBlocking {
        leaveMarker("stale", favourite = true)
        save(film("live", ud()))
        val gate = Mutex()
        val lib = LibraryRepository(gateway, db, appScope, Dispatchers.IO, { now }, log, gate)
        val gated = PlaystateRepository(gateway, lib, appScope, Dispatchers.IO, { now })
        val held = holdMutation()
        gate.withLock {
            val call = background { gated.setFavourite(s1u1, id("live"), true) }
            delay(200) // the sweep is waiting for the gate, so the action is waiting for the sweep
            assertTrue(gateway.calls.isEmpty())
            assertNotNull(userData("stale")!!.pendingSyncAt)
            assertFalse(userData("live")!!.favourite)
            gate.unlock()
            until { gateway.count("setFavourite") == 1 }
            assertNull("swept", userData("stale")!!.pendingSyncAt)
            assertNotNull("the live action's own marker survived the sweep", userData("live")!!.pendingSyncAt)
            held.release()
            withTimeout(5_000) { call.await() }
            gate.lock()
        }
        assertNoPending()
    }

    @Test fun aSweepThatFailedIsTriedAgainAfterThirtySecondsButNotBefore() = runBlocking {
        leaveMarker("a")
        gateway.failAll = AppError.Kind.OFFLINE
        sql("CREATE TRIGGER nope BEFORE UPDATE ON user_data BEGIN SELECT RAISE(ABORT, 'no'); END")
        repo.refresh(s1u1, RefreshTarget.Item(id("a"))) // the sweep fails
        sql("DROP TRIGGER nope")
        repo.refresh(s1u1, RefreshTarget.Item(id("a")))
        assertEquals("not tried again at once", 1, pendingRows())
        advance(Duration.ofSeconds(31))
        repo.refresh(s1u1, RefreshTarget.Item(id("a")))
        assertNoPending()
    }

    @Test fun aSweepThatKeepsFailingIsTriedAtMostFiveTimes() = runBlocking {
        leaveMarker("a")
        gateway.failAll = AppError.Kind.OFFLINE
        sql("CREATE TRIGGER nope BEFORE UPDATE ON user_data BEGIN SELECT RAISE(ABORT, 'no'); END")
        repeat(6) {
            repo.refresh(s1u1, RefreshTarget.Item(id("a")))
            advance(Duration.ofSeconds(31))
        }
        sql("DROP TRIGGER nope")
        repo.refresh(s1u1, RefreshTarget.Item(id("a"))) // a sixth attempt would clear it now
        assertEquals(1, pendingRows())
    }

    @Test fun aSearchWaitsForTheSweepLikeEveryOtherEntryPoint() = runBlocking {
        leaveMarker("a")
        gateway.searchFor = { listOf(film("a", ud(favourite = false))) }
        repo.search(s1u1, "film")
        assertNoPending()
    }

    @Test fun aSweepThatCannotRunDoesNotStopTheLibraryWorking() = runBlocking {
        leaveMarker("a")
        sql("CREATE TRIGGER nope BEFORE UPDATE ON user_data BEGIN SELECT RAISE(ABORT, 'no'); END")
        gateway.failAll = AppError.Kind.OFFLINE
        repo.refresh(s1u1, RefreshTarget.Item(id("a"))) // must return rather than hang or throw
        assertEquals(1, gateway.count("item"))
    }
}
