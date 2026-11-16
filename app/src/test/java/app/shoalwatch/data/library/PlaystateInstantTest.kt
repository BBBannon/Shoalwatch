package app.shoalwatch.data.library

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The to-do item "Make quick ticks instant": a tick returns once saved on this phone, whatever the server is doing. */
class PlaystateInstantTest : PlaystateTestBase() {
    @Test fun twentyTicksWhileTheServerIsHeldAllShowAtOnceAndAreSentInOrderAfterwards() = runBlocking {
        val labels = (1..20).map { "e$it" }
        save(series(data = ud(unplayed = 30)), season("s1", 1, ud(unplayed = 20)), *labels.mapIndexed { i, e -> ep(e, "s1", i + 1) }.toTypedArray())
        val held = holdServer()
        for (e in labels) {
            // Each returns while every send is held at the server: nothing waits for it.
            withTimeout(2_000) { playstate.setPlayed(s1u1, id(e), true) }
        }
        labels.forEach { assertTrue("episode $it", userData(it)!!.played) }
        assertEquals("the season badge moved by 20", 0, userData("s1")!!.unplayedItemCount)
        assertEquals("the series badge moved by 20", 10, userData("show")!!.unplayedItemCount)
        assertEquals(20, queued())
        assertTrue("at most the first send has reached the server", gateway.count("setPlayed") <= 1)
        release(held)
        settle()
        assertEquals(labels.map { "setPlayed:${id(it)}:true" }, gateway.calls.filter { it.startsWith("setPlayed") })
        assertEquals(0, queued())
        assertNoPending()
    }
}
