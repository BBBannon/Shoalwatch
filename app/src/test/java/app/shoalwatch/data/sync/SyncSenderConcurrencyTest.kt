package app.shoalwatch.data.sync

import app.shoalwatch.S1
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.Session
import app.shoalwatch.testId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/** Tasks 3-5 review I1 and M1: a joined kick is never lost, and a forget right after a kick holds. */
class SyncSenderConcurrencyTest : SyncSenderTestBase() {
    /** I1: a kick that joins a drain which has just found nothing more due still gets its new row sent by that drain. */
    @Test fun aKickJoiningADrainThatIsFinishingStillHasItsRowSent() {
        enqueue(s1u1, favourite("a"))
        val joined = CompletableDeferred<kotlinx.coroutines.Deferred<DrainResult>>()
        val once = AtomicBoolean()
        sender.beforeIdle = { s ->
            if (once.compareAndSet(false, true)) {
                outbox.enqueue(s, favourite("b"), OutboxStore.Marks(listOf(id("b")), emptyList()), listOf("resume")) // a change as the drain ends
                joined.complete(sender.kick(s))
            }
        }
        val first = sender.kick(s1u1)
        val result = runBlocking { withTimeout(10_000) { joined.await().await() } }
        assertEquals(DrainResult.EMPTY, result)
        assertEquals(DrainResult.EMPTY, runBlocking { withTimeout(10_000) { first.await() } })
        assertEquals("the joined drain sent the new row", listOf(id("a"), id("b")), gateway.calls.map { it.itemId })
        assertEquals(0, count("sync_outbox"))
    }

    /** M1: a forget straight after a kick, before the drain has even started, still stops it. */
    @Test fun aForgetStraightAfterAKickStopsTheDrainBeforeItSendsAnything() {
        enqueue(s1u1, favourite("a"))
        val scheduler = TestCoroutineScheduler()
        val io = StandardTestDispatcher(scheduler)
        val scope = CoroutineScope(SupervisorJob() + io)
        val held = SyncSender(outbox, gateway, landing, { true }, {}, scope, { clock }, io)
        val result = held.kick(s1u1)
        held.forget(s1u1.serverId, s1u1.userId)
        repeat(200) { scheduler.advanceUntilIdle(); Thread.sleep(5) } // let anything that was not cancelled run (Room answers on its own threads)
        assertTrue(result.isCompleted)
        assertEquals("nothing sent for the forgotten account", emptyList<FakeSyncGateway.Call>(), gateway.calls.toList())
        assertEquals(1, count("sync_outbox"))
        scope.cancel()
    }
}
