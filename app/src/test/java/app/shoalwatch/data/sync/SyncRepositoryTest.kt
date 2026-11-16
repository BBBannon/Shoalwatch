package app.shoalwatch.data.sync

import app.shoalwatch.core.AppError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The seam screens read: one profile's count at a time, never another's; [Sync.sendNow] waits at most its timeout and leaves the drain running. */
class SyncRepositoryTest : SyncSenderTestBase() {
    private val signedIn = MutableStateFlow(setOf(s1u1.serverId to s1u1.userId, s1u2.serverId to s1u2.userId))
    private lateinit var sync: SyncRepository

    @Before fun openSync() {
        sync = SyncRepository(outbox, sender, signedIn)
    }

    private fun status(s: app.shoalwatch.data.servers.Session) = runBlocking { withTimeout(5_000) { sync.status(s).first() } }

    @Test fun statusIsPerProfileAndAnotherProfilesRowsAreNeverCounted() {
        enqueue(s1u1, favourite("a"))
        enqueue(s1u1, played("b"))
        enqueue(s1u2, favourite("c"))
        enqueue(s2u1, favourite("d"))
        assertEquals(SyncStatus(waiting = 2, stuck = 0, needsSignIn = false, lastSyncedAt = null), status(s1u1))
        assertEquals(1, status(s1u2).waiting)
        assertEquals(1, status(s2u1).waiting)
        assertEquals(2, runBlocking { sync.waiting(s1u1) })
        assertEquals(1, runBlocking { sync.waiting(s1u2) })
    }

    @Test fun aProfileWithRowsAndNoLoadedTokenNeedsASignIn() {
        enqueue(s2u1, favourite("d"))
        assertTrue("S2/U1 is not in the usable set", status(s2u1).needsSignIn)
        assertFalse(status(s1u1).needsSignIn) // nothing waiting: nothing to sign in for
    }

    @Test fun afterASyncTheStatusSaysWhen() {
        enqueue(s1u1, favourite("a"))
        assertEquals(DrainResult.EMPTY, drain(s1u1))
        val after = status(s1u1)
        assertEquals(0, after.waiting)
        assertEquals(clock, after.lastSyncedAt)
        assertEquals("the other profile has never synced", null, status(s1u2).lastSyncedAt)
    }

    @Test fun sendNowSendsAndReturnsWhatIsLeft() {
        enqueue(s1u1, favourite("a"))
        enqueue(s1u2, favourite("c"))
        assertEquals(0, runBlocking { sync.sendNow(s1u1) })
        assertEquals("only as that profile", setOf(s1u1), gateway.calls.map { it.session }.toSet())
        assertEquals(1, runBlocking { sync.waiting(s1u2) })
    }

    @Test fun sendNowGivesUpAtTheTimeoutWithoutCancellingTheDrain() = runTest {
        enqueue(s1u1, favourite("a"))
        val gate = CompletableDeferred<Unit>()
        gateway.gate = gate
        val left = sync.sendNow(s1u1, timeoutMs = 10_000)
        assertEquals("gave up after exactly the timeout (virtual time)", 10_000, currentTime)
        assertEquals(1, left)
        gate.complete(Unit)
        val result = runBlocking { withTimeout(10_000) { sender.kick(s1u1).await() } } // joins the drain still running
        assertEquals(DrainResult.EMPTY, result)
        assertEquals(listOf("favourite"), gateway.ops())
        assertEquals(0, sync.waiting(s1u1))
    }

    @Test fun sendNowNeverThrows() {
        enqueue(s1u1, favourite("a"))
        gateway.failNext("*", AppError.Kind.SERVER_ERROR)
        assertEquals("deferred, still waiting", 1, runBlocking { sync.sendNow(s1u1) })
        db.close()
        assertEquals("a failed read counts as nothing to send, without throwing", 0, runBlocking { sync.sendNow(s1u1) })
    }

    @Test fun aFailingReadEmitsNone() {
        enqueue(s1u1, favourite("a"))
        db.close()
        assertEquals(SyncStatus.NONE, status(s1u1))
        assertEquals(0, runBlocking { sync.waiting(s1u1) })
    }
}
