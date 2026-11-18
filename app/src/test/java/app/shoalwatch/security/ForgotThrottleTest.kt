package app.shoalwatch.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.TestMonotonic
import app.shoalwatch.data.servers.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    /** Security review M8 F5: a signed-out or removed profile's tries do not stay behind; other keys in the file are left alone. */
    @Test fun keepOnlyForgetsTheTriesOfProfilesThatAreGone() {
        val t = ForgotThrottle(prefs, clock)
        val cat = Session("s2", "u1")
        t.failed(ann)
        t.failed(bob)
        t.failed(cat)
        prefs.edit().putBoolean("app_lock", true).commit()
        t.keepOnly(setOf(bob))
        assertEquals(30, t.waitSeconds(bob))
        assertEquals(0, t.waitSeconds(ann))
        assertEquals(0, t.waitSeconds(cat))
        assertEquals(setOf("app_lock", "forgot_failures/s1/u2", "forgot_at/s1/u2"), prefs.all.keys)
    }

    /** The two hooks that fire when a profile or a server goes ([AppGraph]'s `ServerStore` wiring) both prune the tries. */
    @Test fun signOutAndServerRemovalPruneTheTries() {
        val graph = java.io.File("src/main/java/app/shoalwatch/AppGraph.kt").readText()
        val removed = graph.substringAfter("onServerRemoved = {").substringBefore("},")
        val gone = graph.substringAfter("onAccountGone = {").substringBefore("},")
        assertTrue("server removal", removed.contains("forgetGoneForgotTries()"))
        assertTrue("account gone", gone.contains("forgetGoneForgotTries()"))
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
