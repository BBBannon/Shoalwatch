package app.shoalwatch.data.playback

import app.shoalwatch.TestMonotonic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sleep timer's choices and when a timed one is due (by the monotonic clock). */
class SleepTimerTest {
    @Test fun eachChoiceStartsTheRightState() {
        assertEquals(SleepState.Off, SleepTimer.start(SleepChoice.OFF, 1_000))
        assertEquals(SleepState.EndOfItem, SleepTimer.start(SleepChoice.END_OF_ITEM, 1_000))
        assertEquals(SleepState.Minutes(1_000 + 15 * 60_000L), SleepTimer.start(SleepChoice.MIN_15, 1_000))
        assertEquals(SleepState.Minutes(1_000 + 30 * 60_000L), SleepTimer.start(SleepChoice.MIN_30, 1_000))
        assertEquals(SleepState.Minutes(1_000 + 45 * 60_000L), SleepTimer.start(SleepChoice.MIN_45, 1_000))
        assertEquals(SleepState.Minutes(1_000 + 60 * 60_000L), SleepTimer.start(SleepChoice.MIN_60, 1_000))
    }

    @Test fun aTimedChoiceIsDueOnlyOnceItsTimeHasCome() {
        val s = SleepTimer.start(SleepChoice.MIN_15, 0)
        assertFalse(SleepTimer.isDue(s, 15 * 60_000L - 1))
        assertTrue(SleepTimer.isDue(s, 15 * 60_000L))
    }

    @Test fun offAndEndOfItemAreNeverDueByTime() {
        assertFalse(SleepTimer.isDue(SleepState.Off, Long.MAX_VALUE))
        assertFalse(SleepTimer.isDue(SleepState.EndOfItem, Long.MAX_VALUE))
    }

    @Test fun theControllersTimerIsDueOnceThenOff() {
        val clock = TestMonotonic(0)
        val sleep = SleepControl(clock)
        sleep.set(SleepChoice.MIN_15)
        assertFalse(sleep.takeDue())
        clock.ms = 15 * 60_000L
        assertTrue(sleep.takeDue())
        assertEquals(SleepState.Off, sleep.state)
        assertFalse(sleep.takeDue())
    }

    @Test fun endOfItemIsTakenOnceAndNeverDueByTime() {
        val clock = TestMonotonic(0)
        val sleep = SleepControl(clock)
        sleep.set(SleepChoice.END_OF_ITEM)
        clock.ms = Long.MAX_VALUE
        assertFalse(sleep.takeDue())
        assertTrue(sleep.atEndOfItem)
        assertTrue(sleep.takeEndOfItem())
        assertFalse(sleep.atEndOfItem)
        assertFalse(sleep.takeEndOfItem())
    }
}
