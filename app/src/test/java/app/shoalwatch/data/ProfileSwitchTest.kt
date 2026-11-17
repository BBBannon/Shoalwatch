package app.shoalwatch.data

import app.shoalwatch.S1
import app.shoalwatch.S2
import app.shoalwatch.TestMonotonic
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.data.db.LibraryTestBase
import app.shoalwatch.data.servers.ProfileProtectedException
import app.shoalwatch.data.servers.ProfileSwitch
import app.shoalwatch.data.servers.ProvedSwitches
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.servers.StoreCatalog
import app.shoalwatch.data.servers.SwitchResult
import app.shoalwatch.security.PinCheck
import app.shoalwatch.security.PinHasher
import app.shoalwatch.security.ProfileLocks
import app.shoalwatch.security.SecretBox
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import javax.crypto.KeyGenerator

/**
 * Switching profiles (Milestone 7 Task 3): a protected profile is made active only after its own PIN, and the catalog itself
 * refuses to make one active, so a screen that forgets to ask is still refused. Adult = (S1, U1), kid = (S1, U2).
 */
class ProfileSwitchTest : LibraryTestBase() {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val hasher = PinHasher(SecretBox { key }, iterations = 1_000)
    private val clock = TestMonotonic()
    private val adult = s1u1
    private val kid = s1u2
    private val activated = mutableListOf<Session>()
    private val usable = mutableSetOf(s1u1, s1u2, s2u1)
    private val proved = ProvedSwitches(clock)

    private fun TestScope.locks() = ProfileLocks(db.profileDao(), hasher, clock, StandardTestDispatcher(testScheduler)) {}

    private fun switch(locks: ProfileLocks, setActive: suspend (String, String) -> Unit = { s, u -> activated += Session(s, u) }) =
        ProfileSwitch(setActive, { it in usable }, locks, proved)

    private fun CharArray.isZeroed() = all { it == '\u0000' }

    @Test fun anUnprotectedProfileIsSwitchedToAtOnce() = runTest {
        val result = switch(locks()).switchTo(S1, U2)
        assertEquals(SwitchResult.Switched, result)
        assertEquals(listOf(kid), activated)
    }

    @Test fun aProtectedProfileNeedsItsPinAndNothingIsSet() = runTest {
        val locks = locks()
        locks.protect(adult, "4826".toCharArray())
        assertEquals(SwitchResult.NeedsPin, switch(locks).switchTo(S1, U1))
        assertTrue(activated.isEmpty())
        assertFalse(proved.consume(adult))
    }

    @Test fun aWrongPinSwitchesNothingAndProvesNothing() = runTest {
        val locks = locks()
        locks.protect(adult, "4826".toCharArray())
        val pin = "1111".toCharArray()
        assertEquals(SwitchResult.NotSwitched(PinCheck.Wrong(4)), switch(locks).switchWithPin(S1, U1, pin))
        assertTrue(pin.isZeroed())
        assertTrue(activated.isEmpty())
        assertFalse(proved.consume(adult))
    }

    @Test fun theRightPinSwitchesAndIsProvedOnce() = runTest {
        val locks = locks()
        locks.protect(adult, "4826".toCharArray())
        val pin = "4826".toCharArray()
        assertEquals(SwitchResult.Switched, switch(locks).switchWithPin(S1, U1, pin))
        assertTrue(pin.isZeroed())
        assertEquals(listOf(adult), activated)
        assertTrue(proved.consume(adult))
        assertFalse("consumed once", proved.consume(adult))
    }

    @Test fun theProofIsRecordedBeforeTheProfileIsMadeActive() = runTest {
        val locks = locks()
        locks.protect(adult, "4826".toCharArray())
        var provedWhenSet = false
        val switch = switch(locks) { s, u -> provedWhenSet = proved.consume(Session(s, u)) }
        assertEquals(SwitchResult.Switched, switch.switchWithPin(S1, U1, "4826".toCharArray()))
        assertTrue(provedWhenSet)
    }

    @Test fun aProofLastsTenSecondsAndOnlyForThatProfile() {
        proved.record(adult)
        assertFalse(proved.consume(kid))
        clock.ms += 10_000
        assertTrue(proved.consume(adult))
        proved.record(adult)
        clock.ms += 11_000
        assertFalse("an unconsumed proof expires", proved.consume(adult))
    }

    @Test fun anExpiredOrRemovedProfileIsGoneAndEveryPinIsZeroed() = runTest {
        val locks = locks()
        locks.protect(adult, "4826".toCharArray())
        usable -= adult
        usable -= kid
        assertEquals(SwitchResult.Gone, switch(locks).switchTo(S1, U1))
        assertEquals(SwitchResult.Gone, switch(locks).switchTo(S1, U2))
        val pin = "4826".toCharArray()
        assertEquals(SwitchResult.Gone, switch(locks).switchWithPin(S1, U1, pin))
        assertTrue(pin.isZeroed())
        assertTrue(activated.isEmpty())
        assertFalse(proved.consume(adult))
    }

    @Test fun aPinForAnUnprotectedProfileSwitchesAsSwitchToAndIsZeroed() = runTest {
        val pin = "4826".toCharArray()
        assertEquals(SwitchResult.Switched, switch(locks()).switchWithPin(S2, U1, pin))
        assertTrue(pin.isZeroed())
        assertEquals(listOf(s2u1), activated)
    }

    @Test fun theCatalogRefusesToMakeAProtectedProfileActive() = runTest {
        val locks = locks()
        servers.warmTokens()
        servers.setActive(S1, U2)
        locks.protect(adult, "4826".toCharArray())
        val catalog = StoreCatalog(servers, { locks.isProtected(it) })
        try {
            catalog.setActive(S1, U1)
            fail("a protected profile was made active without its PIN")
        } catch (_: ProfileProtectedException) {
        }
        assertEquals(U2, servers.active()?.userId)
        catalog.setActive(S2, U1) // unprotected: allowed
        assertEquals(s2u1, servers.active()?.let { Session(it.serverId, it.userId) })
    }

    @Test fun theSwitchMakesAProtectedProfileActiveThroughTheRealStoreAfterItsPin() = runTest {
        val locks = locks()
        servers.warmTokens()
        servers.setActive(S1, U2)
        locks.protect(adult, "4826".toCharArray())
        val switch = ProfileSwitch(servers::setActive, { servers.cachedToken(it.serverId, it.userId) != null }, locks, proved)
        assertEquals(SwitchResult.NeedsPin, switch.switchTo(S1, U1))
        assertEquals(U2, servers.active()?.userId)
        assertEquals(SwitchResult.Switched, switch.switchWithPin(S1, U1, "4826".toCharArray()))
        assertEquals(U1, servers.active()?.userId)
    }
}
