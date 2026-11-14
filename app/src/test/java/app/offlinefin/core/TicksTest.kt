package app.offlinefin.core

import org.junit.Assert.assertEquals
import org.junit.Test

class TicksTest {
    @Test fun conversions() {
        assertEquals(1L, Ticks.toMs(10_000))
        assertEquals(10_000L, Ticks.fromMs(1))
        assertEquals(3_600_000L, Ticks.toMs(36_000_000_000L))
        assertEquals(0L, Ticks.toMs(9_999))
    }
}
