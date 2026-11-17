package app.shoalwatch.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.TestMonotonic
import app.shoalwatch.data.servers.Session
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Tasks 5-6 review M5: "Forgot PIN" waits between wrong server passwords, per profile, kept across restarts, timed monotonically. */
@RunWith(RobolectricTestRunner::class)
class ForgotThrottleTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("forgot-test", 0).also { it.edit().clear().commit() }
    private val clock = TestMonotonic()
    private val ann = Session("s1", "u1")
    private val bob = Session("s1", "u2")

    @Test fun eachWrongPasswordDoublesTheWaitUpToFifteenMinutes() {
        val t = ForgotThrottle(prefs, clock)
        assertEquals(0, t.waitSeconds(ann))
        t.failed(ann)
        assertEquals(30, t.waitSeconds(ann))
        clock.ms += 10_000
        assertEquals(20, t.waitSeconds(ann))
        clock.ms += 20_000
        assertEquals(0, t.waitSeconds(ann))
        t.failed(ann)
        assertEquals(60, t.waitSeconds(ann))
        repeat(10) { t.failed(ann) }
        assertEquals(15 * 60, t.waitSeconds(ann))
        assertEquals("another profile is not held back", 0, t.waitSeconds(bob))
    }

    @Test fun theWaitOutlivesARestartAndARebootNeverShortensIt() {
        ForgotThrottle(prefs, clock).failed(ann)
        clock.ms += 5_000
        assertEquals("a new process still waits", 25, ForgotThrottle(prefs, clock).waitSeconds(ann))
        val rebooted = TestMonotonic(1_000L) // time since boot starts again
        assertEquals("after a reboot the wait starts again in full", 30, ForgotThrottle(prefs, rebooted).waitSeconds(ann))
    }

    @Test fun theRightPasswordEndsTheWait() {
        val t = ForgotThrottle(prefs, clock)
        t.failed(ann)
        t.succeeded(ann)
        assertEquals(0, t.waitSeconds(ann))
        t.failed(ann)
        assertEquals("counting starts again", 30, t.waitSeconds(ann))
    }
}
