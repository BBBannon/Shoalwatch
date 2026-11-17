package app.shoalwatch.security

import app.shoalwatch.S1
import app.shoalwatch.S2
import app.shoalwatch.TestMonotonic
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.data.db.LibraryTestBase
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.ui.FakeCatalog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.KeyGenerator

/**
 * The household check (Milestone 7 Task 3): while a protected profile exists and an unprotected one is active, household
 * settings need a protected profile's PIN, for 60 s of monotonic time. Adult = (S1, U1), kid = (S1, U2), other adult = (S2, U1).
 */
class HouseholdGuardTest : LibraryTestBase() {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val hasher = PinHasher(SecretBox { key }, iterations = 1_000)
    private val clock = TestMonotonic()
    private val adultA = AccountSummary(S1, U1, "ann")
    private val kidA = AccountSummary(S1, U2, "bob")
    private val otherA = AccountSummary(S2, U1, "cat")
    private val adult = s1u1
    private val kid = s1u2
    private val other = s2u1
    private val catalog = FakeCatalog(accounts = listOf(adultA, kidA, otherA), active = kidA)

    private lateinit var locks: ProfileLocks

    private fun TestScope.guard(): HouseholdGuard {
        locks = ProfileLocks(db.profileDao(), hasher, clock, StandardTestDispatcher(testScheduler))
        return HouseholdGuard(locks, catalog, clock, backgroundScope).also { runCurrent() }
    }

    private suspend fun protect(s: app.shoalwatch.data.servers.Session) = assertTrue(locks.protect(s, "4826".toCharArray()))

    @Test fun withNoProtectedProfileEverythingIsAllowed() = runTest {
        val guard = guard()
        assertTrue(guard.allows())
        assertTrue(guard.allowsSignOut(adult))
        assertFalse(guard.askNeeded.first { true })
        guard.require()
    }

    @Test fun aKidNeedsAGrownUpWhoIsListedByName() = runTest {
        val guard = guard()
        protect(adult)
        assertFalse(guard.allows())
        assertTrue(guard.askNeeded.first { it })
        assertEquals(listOf(adultA), guard.grownUps.first { it.isNotEmpty() })
        try {
            guard.require()
            fail("refused")
        } catch (_: HouseholdLockedException) {
        }
    }

    @Test fun theRightPinAllowsForSixtySeconds() = runTest {
        val guard = guard()
        protect(adult)
        val pin = "4826".toCharArray()
        assertEquals(PinCheck.Correct, guard.confirm(adult, pin))
        assertTrue(pin.all { it == '\u0000' })
        assertTrue(guard.allows())
        clock.ms += 59_000
        assertTrue(guard.allows())
        clock.ms += 2_000
        assertFalse("61 s later", guard.allows())
    }

    @Test fun aChangeOfTheActiveProfileEndsThePassAtOnce() = runTest {
        val guard = guard()
        protect(adult)
        guard.confirm(adult, "4826".toCharArray())
        catalog.activeFlow.value = otherA
        runCurrent()
        catalog.activeFlow.value = kidA // and back: the pass stays ended
        runCurrent()
        assertFalse(guard.allows())
    }

    @Test fun endPassEndsIt() = runTest {
        val guard = guard()
        protect(adult)
        guard.confirm(adult, "4826".toCharArray())
        guard.endPass()
        assertFalse(guard.allows())
    }

    @Test fun aWrongPinAllowsNothingAndTheWaitFollowsProfileLocks() = runTest {
        val guard = guard()
        protect(adult)
        assertEquals(PinCheck.Wrong(4), guard.confirm(adult, "1111".toCharArray()))
        assertFalse(guard.allows())
        repeat(4) { guard.confirm(adult, "1111".toCharArray()) }
        assertEquals(PinCheck.Wait(30), guard.confirm(adult, "4826".toCharArray()))
        assertFalse(guard.allows())
    }

    @Test fun aProtectedActiveProfileIsAllowed() = runTest {
        catalog.activeFlow.value = adultA
        val guard = guard()
        protect(adult)
        assertTrue(guard.allows())
        assertFalse(guard.askNeeded.first { !it })
        assertTrue("its own sign-out", guard.allowsSignOut(adult))
    }

    @Test fun signingOutAProtectedProfileNeedsThatProfilesOwnPin() = runTest {
        val guard = guard()
        protect(adult)
        protect(other)
        assertFalse(guard.allowsSignOut(adult))
        guard.confirm(other, "4826".toCharArray())
        assertTrue("other's PIN allows household changes", guard.allows())
        assertFalse("but not signing out the adult", guard.allowsSignOut(adult))
        try {
            guard.requireSignOut(adult)
            fail("refused")
        } catch (_: HouseholdLockedException) {
        }
        guard.confirm(adult, "4826".toCharArray())
        assertTrue(guard.allowsSignOut(adult))
        guard.requireSignOut(adult)
        assertTrue("an unprotected profile can always be signed out", guard.allowsSignOut(kid))
    }

    @Test fun aConfirmWithAnUnprotectedProfileAllowsNothing() = runTest {
        val guard = guard()
        protect(adult)
        val pin = "4826".toCharArray()
        assertEquals(PinCheck.NotProtected, guard.confirm(kid, pin))
        assertTrue(pin.all { it == '\u0000' })
        assertFalse(guard.allows())
    }

    @Test fun itsToStringIsRedacted() = runTest {
        assertEquals("HouseholdGuard(<redacted>)", guard().toString())
    }
}
