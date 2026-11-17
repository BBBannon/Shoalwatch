package app.shoalwatch.security

import app.cash.turbine.test
import app.shoalwatch.S1
import app.shoalwatch.TestMonotonic
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.core.MonotonicClock
import app.shoalwatch.data.db.LibraryTestBase
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.crypto.KeyGenerator

/** PIN protection over in-memory Room, a fake monotonic clock and a test dispatcher. A is (S1, U1), B is (S1, U2). */
class ProfileLocksTest : LibraryTestBase() {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val hasher = PinHasher(SecretBox { key }, iterations = 1_000)
    private val clock = TestMonotonic()
    private val a = s1u1
    private val b = s1u2

    private fun TestScope.locks(h: PinHasher = hasher): ProfileLocks = ProfileLocks(db.profileDao(), h, clock, StandardTestDispatcher(testScheduler)) {}

    private fun pin(text: String) = text.toCharArray()

    private fun CharArray.isZeroed() = all { it == '\u0000' }

    private suspend fun failures(s: Session) = db.profileDao().lock(s.serverId, s.userId)!!.failures

    @Test fun protectThenTheRightPinIsCorrectAndEveryArrayIsZeroed() = runTest {
        val locks = locks()
        val p = pin("4826")
        assertTrue(locks.protect(a, p))
        assertTrue("protect zeroes", p.isZeroed())
        assertTrue(locks.isProtected(a))
        val c = pin("4826")
        assertEquals(PinCheck.Correct, locks.check(a, c))
        assertTrue("check zeroes", c.isZeroed())
        val wrong = pin("1111")
        assertEquals(PinCheck.Wrong(4), locks.check(a, wrong))
        assertTrue("a wrong check zeroes", wrong.isZeroed())
        val old = pin("4826")
        val new = pin("5937")
        assertEquals(PinCheck.Correct, locks.changePin(a, old, new))
        assertTrue("changePin zeroes both", old.isZeroed() && new.isZeroed())
        val u = pin("5937")
        assertEquals(PinCheck.Correct, locks.unprotect(a, u))
        assertTrue("unprotect zeroes", u.isZeroed())
        val invalid = pin("12a")
        assertFalse(locks.protect(a, invalid))
        assertTrue("a refused PIN is zeroed too", invalid.isZeroed())
        val unprotected = pin("4826")
        assertEquals(PinCheck.NotProtected, locks.check(a, unprotected))
        assertTrue(unprotected.isZeroed())
    }

    @Test fun aSecondProtectIsRefusedAndTheFirstPinStillWorks() = runTest {
        val locks = locks()
        assertTrue(locks.protect(a, pin("4826")))
        assertFalse(locks.protect(a, pin("9999")))
        assertEquals(PinCheck.Correct, locks.check(a, pin("4826")))
        assertEquals(PinCheck.Wrong(4), locks.check(a, pin("9999")))
    }

    @Test fun fiveFreeTriesThenAThirtySecondWaitThenTheRightPinResetsTheCount() = runTest {
        val locks = locks()
        locks.protect(a, pin("4826"))
        assertEquals(
            listOf(PinCheck.Wrong(4), PinCheck.Wrong(3), PinCheck.Wrong(2), PinCheck.Wrong(1), PinCheck.Wrong(0)),
            (1..5).map { locks.check(a, pin("0000")) },
        )
        assertEquals(5, failures(a))
        assertEquals("even the right PIN is not checked", PinCheck.Wait(30), locks.check(a, pin("4826")))
        assertEquals("waiting does not count", 5, failures(a))
        clock.ms += 29_001
        assertEquals("rounded up", PinCheck.Wait(1), locks.check(a, pin("4826")))
        clock.ms += 999
        assertEquals(PinCheck.Correct, locks.check(a, pin("4826")))
        assertEquals(0, failures(a))
        assertEquals(PinCheck.Wrong(4), locks.check(a, pin("0000")))
    }

    @Test fun eachWrongPinAfterAWaitDoublesIt() = runTest {
        val locks = locks()
        locks.protect(a, pin("4826"))
        repeat(5) { locks.check(a, pin("0000")) }
        clock.ms += 30_000
        assertEquals(PinCheck.Wrong(0), locks.check(a, pin("0000")))
        assertEquals(PinCheck.Wait(60), locks.check(a, pin("0000")))
    }

    @Test fun aRestartStartsTheWaitAgainAndNeverShortensIt() = runTest {
        locks().protect(a, pin("4826"))
        db.profileDao().setFailures(S1, U1, 6)
        clock.ms += 10 * 60_000 // a long time, but no anchor survives the old process
        val fresh = locks()
        assertEquals(PinCheck.Wait(60), fresh.check(a, pin("4826")))
        clock.ms += 59_000
        assertEquals(PinCheck.Wait(1), fresh.check(a, pin("4826")))
        clock.ms += 1_000
        assertEquals(PinCheck.Correct, fresh.check(a, pin("4826")))
    }

    @Test fun theWallClockIsNeverRead() {
        val text = File("src/main/java/app/shoalwatch/security/ProfileLocks.kt").readText()
        for (wall in listOf("System.currentTimeMillis", "Instant.now", "java.time.Clock", "Date()", "SystemClock"))
            assertFalse("ProfileLocks reads $wall", text.contains(wall))
        val params = ProfileLocks::class.java.constructors.single().parameterTypes.toList()
        assertEquals(listOf(app.shoalwatch.data.db.profile.ProfileDao::class.java, PinHasher::class.java, MonotonicClock::class.java, CoroutineDispatcher::class.java, kotlin.jvm.functions.Function1::class.java), params)
    }

    @Test fun changePinWithAWrongOldPinChangesNothing() = runTest {
        val locks = locks()
        locks.protect(a, pin("4826"))
        assertEquals(PinCheck.Wrong(4), locks.changePin(a, pin("1111"), pin("5937")))
        assertEquals(PinCheck.Correct, locks.check(a, pin("4826")))
        assertEquals(PinCheck.Correct, locks.changePin(a, pin("4826"), pin("5937")))
        assertEquals(PinCheck.Wrong(4), locks.check(a, pin("4826")))
        assertEquals(PinCheck.Correct, locks.check(a, pin("5937")))
    }

    @Test fun changePinToAnInvalidPinChecksNothingAndChangesNothing() = runTest {
        val locks = locks()
        locks.protect(a, pin("4826"))
        val old = pin("4826")
        val bad = pin("12")
        val result = runCatching { locks.changePin(a, old, bad) }
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
        assertTrue(old.isZeroed() && bad.isZeroed())
        assertEquals("no try was counted", 0, failures(a))
        assertEquals(PinCheck.Correct, locks.check(a, pin("4826")))
    }

    @Test fun unprotectWithTheRightPinDropsTheProfile() = runTest {
        val locks = locks()
        locks.protect(a, pin("4826"))
        locks.protect(b, pin("1357"))
        assertEquals(setOf(a, b), locks.protectedProfiles.first())
        locks.isProtectedFlow(a).test {
            assertTrue(awaitItem())
            assertEquals(PinCheck.Wrong(4), locks.unprotect(a, pin("0000")))
            assertTrue(locks.isProtected(a))
            assertEquals(PinCheck.Correct, locks.unprotect(a, pin("4826")))
            assertFalse(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
        assertFalse(locks.isProtected(a))
        assertEquals(setOf(b), locks.protectedProfiles.first())
        assertEquals(PinCheck.NotProtected, locks.check(a, pin("4826")))
    }

    @Test fun clearRemovesProtectionWithoutAPin() = runTest {
        val locks = locks()
        locks.protect(a, pin("4826"))
        repeat(6) { locks.check(a, pin("0000")) }
        locks.clear(ClearProof.afterPasswordSignIn(a, app.shoalwatch.data.servers.AccountSummary(a.serverId, a.userId, "ann"))!!)
        assertFalse(locks.isProtected(a))
        assertTrue("a new PIN can be set and has no old wait", locks.protect(a, pin("2468")))
        assertEquals(PinCheck.Correct, locks.check(a, pin("2468")))
    }

    @Test fun aVerifierFromAnotherKeyIsUnreadableAndCountsNothing() = runTest {
        val otherKey = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        locks(PinHasher(SecretBox { otherKey }, iterations = 1_000)).protect(a, pin("4826"))
        val locks = locks()
        assertEquals(PinCheck.Unreadable, locks.check(a, pin("4826")))
        assertEquals(PinCheck.Unreadable, locks.check(a, pin("0000")))
        assertEquals(0, failures(a))
        assertEquals(PinCheck.Unreadable, locks.changePin(a, pin("4826"), pin("5937")))
        assertEquals(PinCheck.Unreadable, locks.unprotect(a, pin("4826")))
        assertTrue(locks.isProtected(a))
    }

    @Test fun oneProfilesFailuresNeverAffectAnother() = runTest {
        val locks = locks()
        locks.protect(a, pin("4826"))
        locks.protect(b, pin("1357"))
        repeat(5) { locks.check(a, pin("0000")) }
        assertEquals(PinCheck.Wait(30), locks.check(a, pin("4826")))
        assertEquals(0, failures(b))
        assertEquals(PinCheck.Wrong(4), locks.check(b, pin("0000")))
        assertEquals(PinCheck.Correct, locks.check(b, pin("1357")))
        assertEquals(5, failures(a))
    }

    /** Review I1: a wrong PIN is counted BEFORE the verifier is tried, so a count that cannot be saved gives no free guesses. */
    @Test fun whenTheCountCannotBeSavedNoPinIsEverCorrect() = runTest {
        locks().protect(a, pin("4826"))
        val real = db.profileDao()
        var full = true
        val dao = object : app.shoalwatch.data.db.profile.ProfileDao by real {
            override suspend fun setFailures(serverId: String, userId: String, failures: Int): Int =
                if (full) throw android.database.sqlite.SQLiteFullException("database or disk is full") else real.setFailures(serverId, userId, failures)
        }
        val locks = ProfileLocks(dao, hasher, clock, StandardTestDispatcher(testScheduler)) {}
        listOf("4826", "0000").forEach { text ->
            val p = pin(text)
            try {
                val answer = locks.check(a, p)
                org.junit.Assert.fail("answered $answer without saving the count")
            } catch (_: app.shoalwatch.core.AppError) {
            }
            assertTrue(p.isZeroed())
        }
        assertEquals("nothing was counted, and nothing was checked", 0, failures(a))
        full = false
        repeat(5) { locks.check(a, pin("0000")) }
        assertEquals("the count and wait still apply", PinCheck.Wait(30), locks.check(a, pin("4826")))
        clock.ms += 30_000
        assertEquals(PinCheck.Correct, locks.check(a, pin("4826")))
        assertEquals("reset on Correct", 0, failures(a))
    }

    @Test fun toStringNamesNothing() = runTest {
        assertEquals("ProfileLocks(<redacted>)", locks().toString())
    }
}
