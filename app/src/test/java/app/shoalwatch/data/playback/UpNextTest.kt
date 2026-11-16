package app.shoalwatch.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "Up next" countdown: ten seconds of playing time, cancellable, or waiting for the user without autoplay. */
class UpNextTest {
    @Test fun countsDownFromTenAndIsDueAtZero() {
        val c = UpNextCountdown("next", "Episode 2", autoplay = true)
        assertEquals(UpNextState("next", "Episode 2", 10, cancelled = false), c.state)
        c.advance(250); assertEquals(10, c.state.secondsLeft)
        c.advance(750); assertEquals(9, c.state.secondsLeft)
        c.advance(8_999); assertFalse(c.due); assertEquals(1, c.state.secondsLeft)
        c.advance(1); assertTrue(c.due); assertEquals(0, c.state.secondsLeft)
        c.advance(5_000); assertEquals(0, c.state.secondsLeft)
    }

    @Test fun withoutAutoplayItWaitsForTheUser() {
        val c = UpNextCountdown("next", null, autoplay = false)
        c.advance(60_000)
        assertFalse(c.due)
        assertEquals(null, c.state.secondsLeft)
    }

    @Test fun cancelledIsNeverDue() {
        val c = UpNextCountdown("next", null, autoplay = true)
        c.advance(3_000); c.cancel(); c.advance(20_000)
        assertFalse(c.due)
        assertEquals(UpNextState("next", null, null, cancelled = true), c.state)
    }
}
