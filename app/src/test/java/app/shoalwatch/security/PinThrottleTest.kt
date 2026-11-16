package app.shoalwatch.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinThrottleTest {
    @Test fun noWaitForTheFreeTries() {
        for (failures in 0..4) assertEquals("failures=$failures", 0L, PinThrottle.waitMs(failures))
    }

    @Test fun thirtySecondsDoublingToFifteenMinutes() {
        assertEquals(30_000L, PinThrottle.waitMs(5))
        assertEquals(60_000L, PinThrottle.waitMs(6))
        assertEquals(480_000L, PinThrottle.waitMs(9))
        assertEquals(900_000L, PinThrottle.waitMs(10))
        assertEquals(900_000L, PinThrottle.waitMs(50))
        assertEquals("no overflow however many", 900_000L, PinThrottle.waitMs(Int.MAX_VALUE))
    }

    @Test fun pinRulesAcceptFourToTwelveAsciiDigitsOnly() {
        assertTrue(PinRules.valid("1234".toCharArray()))
        assertTrue(PinRules.valid("123456789012".toCharArray()))
        for (bad in listOf("123", "1234567890123", "12a4", "\u0661\u0662\u0663\u0664", "", "12 4", "+1234"))
            assertFalse("\"$bad\" is not a PIN", PinRules.valid(bad.toCharArray()))
    }
}
