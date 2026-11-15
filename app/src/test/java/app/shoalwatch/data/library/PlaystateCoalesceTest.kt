package app.shoalwatch.data.library

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Refreshes asked for after actions are queued behind the one running, and coalesced: many actions do not mean many requests. */
class PlaystateCoalesceTest : PlaystateTestBase() {
    @Test fun twentyWatchedMarksWhileAResumeRefreshIsHeldCostAtMostTwoMoreResumeRequests() = runBlocking {
        val labels = (1..20).map { "m$it" }
        saveResume(*labels.map { film(it) }.toTypedArray())
        gateway.resumeFor = { emptyList() }
        val l = holdCalls("resume")
        val refreshing = background { repo.refresh(s1u1, RefreshTarget.Home) }
        until { gateway.count("resume") == 1 }
        val calls = labels.map { x -> background { playstate.setPlayed(s1u1, id(x), true) } }
        withTimeout(10_000) { calls.forEach { it.await() } } // the actions do not wait for the held refresh
        gateway.resumeFor = { listOf(film("latest")) }
        l.release()
        withTimeout(10_000) { refreshing.await() }
        settle()
        assertTrue("resume was asked for ${gateway.count("resume")} times", gateway.count("resume") <= 3)
        assertEquals("the final list is the server's latest", ids("latest"), resumeIds())
        assertNoPending()
    }
}
