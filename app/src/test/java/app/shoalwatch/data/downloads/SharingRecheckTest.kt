package app.shoalwatch.data.downloads

import app.shoalwatch.S1
import app.shoalwatch.TestMonotonic
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the strict sharing rule's re-ask runs (Tasks 13-16 review I-1): on a session change, when the server comes back and when
 * the app comes to the front, whichever screen is open; never offline; at most once per profile every five minutes; one at a time.
 */
class SharingRecheckTest {
    private val a = Session(S1, U1)
    private val b = Session(S1, U2)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val clock = TestMonotonic()
    private var reachable = true
    private val runs = mutableListOf<Session>()
    private var gate: CompletableDeferred<Unit>? = null
    private val firsts = mutableListOf<String?>()
    private var asked = true
    private val recheck = SharingRecheck({ s, first -> runs += s; firsts += first; gate?.await(); asked }, { reachable }, scope, clock)

    @After fun stop() = scope.cancel()

    /** Final review L-11: the item about to play is asked about first; the other triggers name none. */
    @Test fun aPlayNamesItsItemSoItIsAskedFirst() {
        recheck.request(b, "0123456789abcdef0123456789abcdef")
        recheck.request(a)
        assertEquals(listOf<String?>("0123456789abcdef0123456789abcdef", null), firsts)
    }

    @Test fun aSessionChangeReAsksForTheNewProfile() {
        val active = MutableStateFlow<Session?>(null)
        recheck.watch(active, MutableSharedFlow())
        assertTrue("nobody signed in", runs.isEmpty())
        active.value = a
        active.value = b
        assertEquals(listOf(a, b), runs)
    }

    @Test fun theServerComingBackReAsksForTheActiveProfile() = runBlocking {
        val regained = MutableSharedFlow<Session>()
        recheck.watch(MutableStateFlow(null), regained)
        regained.emit(b)
        assertEquals(listOf(b), runs)
    }

    @Test fun comingToTheFrontReAsksForTheActiveProfile() = runBlocking {
        recheck.inFront(MutableStateFlow(b))?.join()
        assertEquals(listOf(b), runs)
        assertNull("signed out: nothing", SharingRecheck({ s, _ -> runs += s; true }, { true }, scope, clock).inFront(MutableStateFlow(null)))
    }

    @Test fun offlineNothingIsAskedAndTheNextReachableTriggerStillRuns() {
        reachable = false
        assertNull(recheck.request(b))
        assertTrue(runs.isEmpty())
        reachable = true
        recheck.request(b)
        assertEquals(listOf(b), runs)
    }

    @Test fun atMostOneRunPerProfileEveryFiveMinutes() {
        val regained = MutableSharedFlow<Session>(extraBufferCapacity = 8)
        recheck.watch(MutableStateFlow(b), regained)
        repeat(5) { regained.tryEmit(b) } // a flapping connection
        recheck.request(b) // and a play
        assertEquals("one run", listOf(b), runs)
        recheck.request(a)
        assertEquals("another profile is not held back by it", listOf(b, a), runs)
        clock.ms += 5 * 60 * 1_000 - 1
        assertNull(recheck.request(b))
        clock.ms += 1
        recheck.request(b)
        assertEquals(listOf(b, a, b), runs)
    }

    /**
     * Walkthrough W-3: a run that found nothing to ask made no request, so it does not hold back the next trigger (a profile's
     * first run comes at sign-in, before it has browsed to anything another profile downloaded).
     */
    @Test fun aRunWithNothingToAskDoesNotUseUpTheFiveMinutes() {
        asked = false
        recheck.request(b)
        asked = true
        recheck.request(b)
        assertEquals("the second trigger runs at once", listOf(b, b), runs)
        assertNull("that run asked the server, so the gap applies again", recheck.request(b))
    }

    @Test fun oneRunAtATime() = runBlocking {
        val first = CompletableDeferred<Unit>()
        gate = first
        val one: Job = recheck.request(a)!!
        gate = null
        val two: Job = recheck.request(b)!!
        assertEquals("b waits for a", listOf(a), runs)
        first.complete(Unit)
        one.join(); two.join()
        assertEquals(listOf(a, b), runs)
    }
}
