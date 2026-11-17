package app.shoalwatch.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.S1
import app.shoalwatch.S2
import app.shoalwatch.TestMonotonic
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.data.db.LibraryTestBase
import app.shoalwatch.data.db.profile.ProfileDao
import app.shoalwatch.data.db.profile.ProfileLockEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.advanceTimeBy
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.ProvedSwitches
import app.shoalwatch.ui.FakeCatalog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Assert.assertNull
import org.junit.Test
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import java.io.File
import javax.crypto.KeyGenerator

/**
 * The app lock's rules (Milestone 7 Task 4) on a fake monotonic clock, the fake catalog and the real [ProfileLocks] and
 * [ProvedSwitches]. Adult = (S1, U1, "ann"), kid = (S1, U2, "bob"), other = (S2, U1, "cat").
 */
class LockGateTest : LibraryTestBase() {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val hasher = PinHasher(SecretBox { key }, iterations = 1_000)
    private val clock = TestMonotonic()
    private val adultA = AccountSummary(S1, U1, "ann")
    private val kidA = AccountSummary(S1, U2, "bob")
    private val otherA = AccountSummary(S2, U1, "cat")
    private val adult = s1u1
    private val catalog = FakeCatalog(accounts = listOf(adultA, kidA, otherA), active = kidA)
    private val proved = ProvedSwitches(clock)
    private var secure = true
    private lateinit var prefs: LockPrefs
    private lateinit var locks: ProfileLocks
    private lateinit var household: HouseholdGuard

    @Before fun freshPrefs() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = LockPrefs(context.getSharedPreferences("lock-gate-test", 0).also { it.edit().clear().commit() })
    }

    /** Review M6: makes the protected set's read, and the store's own "is it protected?", fail like a broken database. */
    private val readsFail = MutableStateFlow(false)

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun failingDao(real: ProfileDao) = object : ProfileDao by real {
        override fun observeProtected() = readsFail.flatMapLatest { failing ->
            if (failing) flow { throw android.database.sqlite.SQLiteException("disk I/O error") } else real.observeProtected()
        }
        override suspend fun lock(serverId: String, userId: String) =
            if (readsFail.value) throw android.database.sqlite.SQLiteException("disk I/O error") else real.lock(serverId, userId)
    }

    private fun TestScope.gate(): LockGate {
        locks = ProfileLocks(failingDao(db.profileDao()), hasher, clock, StandardTestDispatcher(testScheduler)) {}
        household = HouseholdGuard(locks, catalog, clock, backgroundScope) { true }
        return LockGate(prefs.settings, locks, catalog, proved, household, { secure }, clock, backgroundScope) { prefs.setAppLock(false) }
    }

    /**
     * Lets work the gate has waiting on Room's own threads finish (the store's answer to "is it protected?"), for the checks that
     * something did NOT happen. Real time only lets those threads run; every rule's time is the fake clock.
     */
    private suspend fun TestScope.settle() = repeat(20) {
        advanceUntilIdle()
        withContext(Dispatchers.IO) { Thread.sleep(25) }
    }

    private suspend fun LockGate.settled(): LockState = state.first { it != LockState.Checking }

    private suspend fun protectAdult() = assertTrue(locks.protect(adult, "4826".toCharArray()))

    /** Starts with the adult protected and active, unlocked, as after typing the PIN. */
    private suspend fun TestScope.openAsAdult(): LockGate {
        val gate = gate()
        protectAdult()
        catalog.activeFlow.value = adultA
        gate.start()
        assertEquals(LockState.Profile(adult, "ann"), gate.settled())
        assertEquals(PinCheck.Correct, gate.unlockProfile("4826".toCharArray()))
        return gate
    }

    // ---- review M6: a read that fails never crashes and never opens

    @Test fun aBrokenReadOfTheProtectedSetKeepsTheLockAndSaysSo() = runTest {
        val gate = gate()
        protectAdult()
        catalog.activeFlow.value = adultA
        gate.start()
        assertEquals(LockState.Profile(adult, "ann"), gate.settled())
        readsFail.value = true
        settle()
        assertEquals(LockState.Profile(adult, "ann"), gate.state.value)
        assertTrue("a plain error to show", gate.readFailed.value)
        assertNull(gate.openFor.value)
        readsFail.value = false
        advanceTimeBy(LockGate.READ_RETRY_MS + 1)
        settle()
        assertFalse("read again once it works", gate.readFailed.value)
        assertEquals(LockState.Profile(adult, "ann"), gate.state.value)
    }

    @Test fun aBrokenReadAfterForgotPinStaysLockedWithoutACrash() = runTest {
        val gate = gate()
        protectAdult()
        catalog.activeFlow.value = adultA
        gate.start()
        assertEquals(LockState.Profile(adult, "ann"), gate.settled())
        readsFail.value = true
        gate.profileCleared(adult)
        settle()
        assertEquals(LockState.Profile(adult, "ann"), gate.state.value)
        assertTrue(gate.readFailed.value)
    }

    // ---- rule 1: start

    @Test fun withNothingOnItChecksThenOpens() = runTest {
        val gate = gate()
        assertEquals(LockState.Checking, gate.state.value)
        gate.start()
        assertEquals(LockState.Open, gate.settled())
    }

    @Test fun withTheAppLockOnItAsksForThePhonesScreenLock() = runTest {
        prefs.setAppLock(true)
        val gate = gate()
        gate.start()
        assertEquals(LockState.App, gate.settled())
    }

    @Test fun aProtectedActiveProfileIsLockedAtStart() = runTest {
        val gate = gate()
        protectAdult()
        catalog.activeFlow.value = adultA
        gate.start()
        assertEquals(LockState.Profile(adult, "ann"), gate.settled())
    }

    @Test fun theAppLockComesFirstThenTheProfile() = runTest {
        prefs.setAppLock(true)
        val gate = gate()
        protectAdult()
        catalog.activeFlow.value = adultA
        gate.start()
        assertEquals(LockState.App, gate.settled())
        gate.appUnlocked()
        assertEquals(LockState.Profile(adult, "ann"), gate.state.value)
    }

    // ---- rule 2: leaving and returning

    @Test fun oneMinuteLocksAtSixtySecondsAway() = runTest {
        val gate = openAsAdult()
        gate.onLeft(); clock.ms += 59_000; gate.onReturned()
        assertEquals(LockState.Open, gate.state.value)
        gate.onLeft(); clock.ms += 60_000; gate.onReturned()
        assertEquals(LockState.Profile(adult, "ann"), gate.state.value)
    }

    @Test fun immediatelyLocksOnAnyLeave() = runTest {
        prefs.setLockAfter(LockAfter.IMMEDIATELY)
        val gate = openAsAdult()
        gate.onLeft(); gate.onReturned()
        assertEquals(LockState.Profile(adult, "ann"), gate.state.value)
    }

    @Test fun aSecondLeaveKeepsTheEarlierTime() = runTest {
        val gate = openAsAdult()
        gate.onLeft(); clock.ms += 30_000
        gate.onLeft(); clock.ms += 30_000
        gate.onReturned()
        assertEquals(LockState.Profile(adult, "ann"), gate.state.value)
    }

    @Test fun anExpectedTripDoesNotCountButTheNextOneDoes() = runTest {
        val gate = openAsAdult()
        gate.expectReturn(); gate.onLeft(); clock.ms += 90_000; gate.onReturned()
        assertEquals(LockState.Open, gate.state.value)
        gate.onLeft(); clock.ms += 600_000; gate.onReturned()
        assertEquals(LockState.Profile(adult, "ann"), gate.state.value)
    }

    @Test fun anExpectedTripThatNeverStartsDoesNotExcuseALaterOne() = runTest {
        val gate = openAsAdult()
        gate.expectReturn() // a prompt that did not stop the activity
        clock.ms += 60_000
        gate.onLeft(); clock.ms += 600_000; gate.onReturned()
        assertEquals(LockState.Profile(adult, "ann"), gate.state.value)
    }

    @Test fun theAppLockReturnsAfterTimeAway() = runTest {
        prefs.setAppLock(true)
        val gate = gate()
        gate.start()
        assertEquals(LockState.App, gate.settled())
        gate.appUnlocked()
        assertEquals(LockState.Open, gate.state.value)
        gate.onLeft(); clock.ms += 60_000; gate.onReturned()
        assertEquals(LockState.App, gate.state.value)
    }

    // ---- rule 3: profile changes

    @Test fun anUnprovedChangeToAProtectedProfileLocksIt() = runTest {
        val gate = gate()
        protectAdult()
        gate.start()
        assertEquals(LockState.Open, gate.settled())
        catalog.activeFlow.value = adultA // as a sign-in, "Sign in again" or a restore sets it
        assertEquals(LockState.Profile(adult, "ann"), gate.state.first { it is LockState.Profile })
    }

    @Test fun aProvedChangeStaysOpen() = runTest {
        val gate = gate()
        protectAdult()
        gate.start()
        assertEquals(LockState.Open, gate.settled())
        proved.record(adult)
        catalog.activeFlow.value = adultA
        settle()
        assertEquals(LockState.Open, gate.state.value)
        assertFalse("the proof was used", proved.consume(adult))
    }

    @Test fun aChangeToAnUnprotectedProfileOrNobodyOpensTheProfileLock() = runTest {
        val gate = gate()
        protectAdult()
        catalog.activeFlow.value = adultA
        gate.start()
        assertEquals(LockState.Profile(adult, "ann"), gate.settled())
        catalog.activeFlow.value = kidA
        assertEquals(LockState.Open, gate.state.first { it == LockState.Open })
        catalog.activeFlow.value = adultA
        gate.state.first { it is LockState.Profile }
        catalog.activeFlow.value = null
        assertEquals(LockState.Open, gate.state.first { it == LockState.Open })
    }

    @Test fun theRightPinOpensAndAWrongOneDoesNot() = runTest {
        val gate = gate()
        protectAdult()
        catalog.activeFlow.value = adultA
        gate.start()
        gate.settled()
        val wrong = "1111".toCharArray()
        assertEquals(PinCheck.Wrong(4), gate.unlockProfile(wrong))
        assertTrue(wrong.all { it == '\u0000' })
        assertEquals(LockState.Profile(adult, "ann"), gate.state.value)
        assertEquals(PinCheck.Correct, gate.unlockProfile("4826".toCharArray()))
        assertEquals(LockState.Open, gate.state.value)
    }

    @Test fun profileClearedOpensOnlyOnceTheProtectionIsGone() = runTest {
        val gate = gate()
        protectAdult()
        catalog.activeFlow.value = adultA
        gate.start()
        gate.settled()
        gate.profileCleared(adult) // still protected in the store: nothing
        settle()
        assertEquals(LockState.Profile(adult, "ann"), gate.state.value)
        locks.clear(ClearProof.afterPasswordSignIn(adult, adultA)!!)
        gate.profileCleared(adult)
        assertEquals(LockState.Open, gate.state.first { it == LockState.Open })
    }

    // ---- rule 5: no secure screen lock

    @Test fun withoutASecureScreenLockTheAppLockIsTurnedOff() = runTest {
        prefs.setAppLock(true)
        secure = false
        val gate = gate()
        gate.start()
        assertEquals(LockState.Open, gate.settled())
        assertFalse(prefs.settings.value.appLock)
    }

    @Test fun withoutASecureScreenLockAProtectedProfileIsStillLocked() = runTest {
        prefs.setAppLock(true)
        secure = false
        val gate = gate()
        protectAdult()
        catalog.activeFlow.value = adultA
        gate.start()
        assertEquals(LockState.Profile(adult, "ann"), gate.settled())
        assertFalse(prefs.settings.value.appLock)
    }

    @Test fun turningTheAppLockOffWhileAskingChecksAgain() = runTest {
        prefs.setAppLock(true)
        val gate = gate()
        gate.start()
        assertEquals(LockState.App, gate.settled())
        prefs.setAppLock(false)
        assertEquals(LockState.Open, gate.state.first { it == LockState.Open })
    }

    // ---- rule 6: the household pass

    @Test fun everyMoveIntoALockEndsTheHouseholdPass() = runTest {
        val gate = gate()
        protectAdult()
        gate.start()
        gate.settled()
        assertEquals(PinCheck.Correct, household.confirm(adult, "4826".toCharArray()))
        assertTrue(household.allows())
        prefs.setLockAfter(LockAfter.IMMEDIATELY)
        prefs.setAppLock(true)
        advanceUntilIdle()
        gate.onLeft(); gate.onReturned()
        assertEquals(LockState.App, gate.state.value)
        assertFalse("into App", household.allows())
    }

    @Test fun aMoveIntoTheProfileLockEndsThePassToo() = runTest {
        val gate = openAsAdult()
        assertTrue(locks.protect(s2u1, "5937".toCharArray()))
        assertEquals(PinCheck.Correct, household.confirm(s2u1, "5937".toCharArray()))
        assertTrue(household.allowsSignOut(s2u1))
        gate.onLeft(); clock.ms += 60_000; gate.onReturned()
        assertEquals(LockState.Profile(adult, "ann"), gate.state.value)
        assertFalse("into Profile", household.allowsSignOut(s2u1))
    }

    // ---- rule 7: recents

    @Test fun theRecentsPreviewIsHiddenForEachOfTheThreeCauses() = runTest {
        val gate = gate()
        gate.start()
        assertFalse(gate.hideRecents.first { !it })
        prefs.setHideInRecents(true)
        assertTrue(gate.hideRecents.first { it })
        prefs.setHideInRecents(false)
        gate.hideRecents.first { !it }
        prefs.setAppLock(true)
        assertTrue(gate.hideRecents.first { it })
        prefs.setAppLock(false)
        gate.hideRecents.first { !it }
        protectAdult()
        assertTrue(gate.hideRecents.first { it })
    }

    @Test fun profileStateIsRedactedAndTheWallClockIsNeverRead() {
        assertEquals("Profile(<redacted>)", LockState.Profile(adult, "ann").toString())
        val source = File("src/main/java/app/shoalwatch/security/LockGate.kt").readText()
        listOf("System.currentTimeMillis", "Instant.now", "Clock.system", "Date(", "SystemClock").forEach {
            assertFalse("$it in LockGate", it in source)
        }
    }

    // ---- review I3: the gate decides at once, and nothing is open for a profile it has not judged

    /** A gate whose store reads never answer (its own [ProfileLocks] runs on a dispatcher nobody advances). */
    /**
     * Holds the store's own "is it protected?" until completed (review M4). The earlier frozen dispatcher on a second scheduler did
     * not hold it: the read failed at once, the gate took that as "protected" and locked, so the I3 test passed for the wrong reason.
     */
    private val storeHeld = CompletableDeferred<Unit>()

    private fun TestScope.frozenGate(): LockGate {
        gate()
        val real = db.profileDao()
        val held = object : ProfileDao by real {
            override suspend fun lock(serverId: String, userId: String): ProfileLockEntity? {
                storeHeld.await()
                return real.lock(serverId, userId)
            }
        }
        val frozen = ProfileLocks(held, hasher, clock, StandardTestDispatcher(testScheduler)) {}
        return LockGate(prefs.settings, frozen, catalog, proved, household, { secure }, clock, backgroundScope) { prefs.setAppLock(false) }
    }

    @Test fun aChangeToAProtectedProfileLocksAtOnceWithoutReadingTheStore() = runTest {
        val gate = frozenGate()
        protectAdult()
        gate.start()
        assertEquals(LockState.Open, gate.settled())
        gate.hideRecents.first { it } // the protected set is known
        catalog.activeFlow.value = adultA
        runCurrent()
        assertEquals(LockState.Profile(adult, "ann"), gate.state.value)
        assertNull(gate.openFor.value)
    }

    @Test fun anUnprotectedChangeIsJudgedOpenOnlyOnceTheStoreHasAnswered() = runTest {
        val gate = frozenGate()
        protectAdult()
        gate.start()
        assertEquals(LockState.Open, gate.settled())
        assertEquals(Judged(Session(S1, U2)), gate.openFor.value)
        catalog.activeFlow.value = otherA
        runCurrent()
        assertEquals("the change was stepped and is not locked", LockState.Open, gate.state.value)
        assertNull("not judged while the store has not answered", gate.openFor.value)
        assertFalse(gate.isOpenFor(Session(S2, U1)))
        storeHeld.complete(Unit)
        settle()
        assertEquals("judged once the store says it is not protected", Judged(Session(S2, U1)), gate.openFor.value)
    }

    @Test fun nothingIsOpenUntilTheGateHasJudgedTheFirstProfile() = runTest {
        val gate = gate()
        protectAdult()
        catalog.activeFlow.value = adultA
        assertNull("before start", gate.openFor.value)
        gate.start()
        assertEquals(LockState.Profile(adult, "ann"), gate.settled())
        assertNull(gate.openFor.value)
        assertFalse(gate.isOpenFor(adult))
        assertEquals(PinCheck.Correct, gate.unlockProfile("4826".toCharArray()))
        assertEquals(Judged(adult), gate.openFor.value)
        assertTrue(gate.isOpenFor(adult))
    }

    @Test fun aKidToAdultToKidChangeIsNeverOpenForTheAdultWhateverTheGateSaw() = runTest {
        val gate = gate()
        protectAdult()
        gate.start()
        assertEquals(LockState.Open, gate.settled())
        gate.hideRecents.first { it }
        val seen = mutableListOf<Pair<Session?, Boolean>>()
        // A screen collecting the active session at once, as every ViewModel does: what it may show is what the gate has opened.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { catalog.activeSession().collect { seen += it to gate.isOpenFor(it) } }
        catalog.activeFlow.value = adultA
        catalog.activeFlow.value = kidA
        settle()
        assertTrue("the screen saw the adult", seen.any { it.first == adult })
        assertTrue("never open for the adult: $seen", seen.none { it.first == adult && it.second })
        assertEquals(Judged(Session(S1, U2)), gate.openFor.value)
    }

    @Test fun anExcusedTripStillLocksAfterTwoMinutes() = runTest {
        val gate = openAsAdult()
        gate.expectReturn(); gate.onLeft(); clock.ms += LockGate.EXCUSED_CAP_MS; gate.onReturned()
        assertEquals(LockState.Profile(adult, "ann"), gate.state.value)
    }
}
