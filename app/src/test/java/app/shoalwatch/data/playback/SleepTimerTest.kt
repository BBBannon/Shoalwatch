package app.shoalwatch.data.playback

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
}
