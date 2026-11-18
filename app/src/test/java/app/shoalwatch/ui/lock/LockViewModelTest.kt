package app.shoalwatch.ui.lock

import android.content.Context
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.S1
import app.shoalwatch.S2
import app.shoalwatch.TestMonotonic
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.LibraryTestBase
import app.shoalwatch.data.db.profile.ProfileDao
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.ProvedSwitches
import app.shoalwatch.data.servers.Session
import app.shoalwatch.security.ClearProof
import app.shoalwatch.security.ForgotThrottle
import app.shoalwatch.security.HouseholdGuard
import app.shoalwatch.security.LockGate
import app.shoalwatch.security.LockPrefs
import app.shoalwatch.security.LockState
import app.shoalwatch.security.PinCheck
import app.shoalwatch.security.PinHasher
import app.shoalwatch.security.ProfileLocks
import app.shoalwatch.security.SecretBox
import app.shoalwatch.ui.FakeAuthenticator
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.testServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.crypto.KeyGenerator

/**
 * The lock screen's ViewModel (Milestone 7 Task 5) over a real [LockGate] and [ProfileLocks] (in-memory Room), the fake catalog,
 * switcher and authenticator. Adult = (S1, U1, "ann", protected), kid = (S1, U2, "bob"), other = (S2, U1, "cat").
 */
class LockViewModelTest : LibraryTestBase() {
    @get:Rule val main = MainDispatcherRule()

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val clock = TestMonotonic()
    private val adultA = AccountSummary(S1, U1, "ann")
    private val kidA = AccountSummary(S1, U2, "bob")
    private val otherA = AccountSummary(S2, U1, "cat")
    private val adult = Session(S1, U1)
    private val other = Session(S2, U1)
    private val catalog = FakeCatalog(listOf(testServer(id = S1), testServer(id = S2)), listOf(adultA, kidA, otherA), adultA)
    private val switcher = FakeSwitcher(catalog)
    private val auth = FakeAuthenticator(catalog)
    private lateinit var locks: ProfileLocks
    private lateinit var gate: LockGate
    private val cleared = mutableListOf<Session>()
    private var full = false
    private val forgotPrefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("lock-vm-forgot", 0).also { it.edit().clear().commit() }

    @Before fun names() {
        catalog.serverList.value = listOf(testServer(id = S1).copy(name = "Test Jellyfin"), testServer(id = S2).copy(name = "Cabin"))
    }

    private suspend fun TestScope.settle() = repeat(15) {
        advanceUntilIdle()
        withContext(Dispatchers.IO) { Thread.sleep(20) }
    }

    private suspend fun TestScope.vm(
        protectOther: Boolean = false,
        protection: (Flow<Set<Session>>) -> Flow<Set<Session>> = { it },
    ): LockViewModel {
        val real = db.profileDao()
        val dao = object : ProfileDao by real {
            override suspend fun setFailures(serverId: String, userId: String, failures: Int): Int =
                if (full) throw android.database.sqlite.SQLiteFullException("database or disk is full") else real.setFailures(serverId, userId, failures)
        }
        locks = ProfileLocks(dao, PinHasher(SecretBox { key }, iterations = 1_000), clock, Dispatchers.IO) {}
        val household = HouseholdGuard(locks, catalog, clock, backgroundScope) { true }
        val prefs = LockPrefs(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("lock-vm", 0).also { it.edit().clear().commit() }) {}
        gate = LockGate(prefs.settings, locks, catalog, ProvedSwitches(clock), household, { true }, clock, backgroundScope) { }
        assertTrue(locks.protect(adult, "4826".toCharArray()))
        if (protectOther) assertTrue(locks.protect(other, "1357".toCharArray()))
        gate.start()
        val vm = LockViewModel(gate, switcher, catalog, auth, protection(locks.protectedProfiles), ForgotThrottle(forgotPrefs, clock)) { proof ->
            cleared += proof.session
            locks.clear(proof)
        }
        settle()
        assertEquals(LockState.Profile(adult, "ann"), gate.state.value)
        return vm
    }

    @Test fun theRightPinOpensAndTheFieldIsEmpty() = runTest {
        val vm = vm()
        vm.pin.setTextAndPlaceCursorAtEnd("4826")
        vm.submitPin()
        settle()
        assertEquals(LockState.Open, gate.state.value)
        assertEquals("", vm.pin.text.toString())
    }

    @Test fun aWrongPinShowsTheAnswerAndEmptiesTheField() = runTest {
        val vm = vm()
        vm.pin.setTextAndPlaceCursorAtEnd("1111")
        vm.submitPin()
        settle()
        assertEquals(PinCheck.Wrong(4), vm.state.value.pinAnswer)
        assertEquals("", vm.pin.text.toString())
        assertTrue(gate.state.value is LockState.Profile)
    }

    @Test fun aTryThatCannotBeSavedIsAPlainErrorNeverRight() = runTest {
        val vm = vm()
        full = true // the count cannot be written (a full phone): the check refuses to check
        vm.pin.setTextAndPlaceCursorAtEnd("4826")
        vm.submitPin()
        settle()
        assertTrue(vm.state.value.pinFailed)
        assertFalse(vm.state.value.busy)
        assertTrue("still locked", gate.state.value is LockState.Profile)
        assertEquals("", vm.pin.text.toString())
    }

    @Test fun othersLeaveOutTheLockedProfileAndExpiredOnesAndMarkProtectedOnes() = runTest {
        catalog.expiredList.value = listOf(AccountSummary(S1, "expired", "old"))
        val vm = vm(protectOther = true)
        val others = vm.state.value.others
        assertEquals(listOf("bob" to false, "cat" to true), others.map { it.userName to it.isProtected })
        assertEquals(listOf("Test Jellyfin", "Cabin"), others.map { it.serverName })
        assertEquals("OtherProfile(<redacted>)", others.first().toString())
    }

    @Test fun aFailedReadOfTheLocksNeverCrashesAndTheMarksComeBack() = runTest {
        // Re-review I-A: the screen's own copy of the protected set must not end the app on a failed read; it reads again.
        var reads = 0
        val vm = vm(protectOther = true) { real ->
            flow {
                if (reads++ == 0) throw android.database.sqlite.SQLiteException("disk I/O error")
                emitAll(real)
            }
        }
        advanceTimeBy(LockGate.READ_RETRY_MS + 1)
        settle()
        assertEquals(2, reads)
        assertEquals(listOf("bob" to false, "cat" to true), vm.state.value.others.map { it.userName to it.isProtected })
    }

    @Test fun anUnprotectedOtherSwitchesOnceAndTheGateOpens() = runTest {
        val vm = vm()
        vm.useProfile(vm.state.value.others.first { it.userName == "bob" })
        settle()
        assertEquals(listOf(Session(S1, U2)), switcher.switchToCalls)
        assertTrue(switcher.pinCalls.isEmpty())
        assertEquals(LockState.Open, gate.state.value)
    }

    @Test fun aProtectedOtherAsksForItsOwnPinAndSwitchesOnlyThatProfile() = runTest {
        val vm = vm(protectOther = true)
        val cat = vm.state.value.others.first { it.userName == "cat" }
        vm.useProfile(cat)
        assertEquals(cat, vm.state.value.choosing)
        assertTrue("no switch before the PIN", switcher.switchToCalls.isEmpty())
        switcher.rightPin = "1357"
        vm.choicePin.setTextAndPlaceCursorAtEnd("0000")
        vm.submitChosenPin()
        settle()
        assertEquals(PinCheck.Wrong(4), vm.state.value.pinAnswer)
        assertEquals(cat, vm.state.value.choosing)
        vm.choicePin.setTextAndPlaceCursorAtEnd("1357")
        vm.submitChosenPin()
        settle()
        assertEquals(listOf(other to "0000", other to "1357"), switcher.pinCalls)
        assertEquals("", vm.choicePin.text.toString())
        assertTrue(switcher.pinArrays.all { a -> a.all { it == '\u0000' } })
    }

    @Test fun aSwitchThatCannotSaveTheTryIsAPlainError() = runTest {
        val vm = vm(protectOther = true)
        vm.useProfile(vm.state.value.others.first { it.userName == "cat" })
        switcher.failure = AppError.of(AppError.Kind.UNKNOWN)
        vm.choicePin.setTextAndPlaceCursorAtEnd("1357")
        vm.submitChosenPin()
        settle()
        assertTrue(vm.state.value.pinFailed)
        assertTrue(gate.state.value is LockState.Profile)
    }

    @Test fun forgotPinOfflineSaysSoAndClearsNothing() = runTest {
        val vm = vm()
        auth.routeAnswer = { null }
        vm.openForgot()
        vm.password.setTextAndPlaceCursorAtEnd("hunter2")
        vm.submitForgot()
        settle()
        assertEquals(AppError.Kind.OFFLINE, vm.state.value.forgotError)
        assertTrue(cleared.isEmpty())
        assertEquals("", vm.password.text.toString())
        assertTrue(gate.state.value is LockState.Profile)
    }

    @Test fun forgotPinWithAnotherAccountsPasswordClearsNothing() = runTest {
        val vm = vm()
        auth.onPassword = { id, u -> AccountSummary(id, "someone-else", u) }
        vm.openForgot()
        vm.password.setTextAndPlaceCursorAtEnd("hunter2")
        vm.submitForgot()
        settle()
        assertTrue(vm.state.value.forgotOtherAccount)
        assertTrue(cleared.isEmpty())
        assertTrue(locks.isProtected(adult))
        assertEquals("", vm.password.text.toString())
    }

    @Test fun forgotPinWithItsOwnPasswordRemovesThePinAndOpens() = runTest {
        val vm = vm()
        auth.onPassword = { id, u -> AccountSummary(id, U1, u) }
        vm.openForgot()
        vm.password.setTextAndPlaceCursorAtEnd("hunter2")
        vm.submitForgot()
        settle()
        assertEquals(listOf(adult), cleared)
        assertEquals(listOf(Triple(S1, "ann", "hunter2")), auth.passwordCalls)
        assertFalse(locks.isProtected(adult))
        assertEquals(LockState.Open, gate.state.value)
        assertEquals("", vm.password.text.toString())
    }

    /** Review M5: a child cannot try server passwords as fast as the server answers (Jellyfin would lock the parent's account). */
    @Test fun forgotPinWaitsAfterAWrongPasswordAndSendsNothingMeanwhile() = runTest {
        val vm = vm()
        auth.onPassword = { _, _ -> throw AppError.of(AppError.Kind.UNAUTHORISED) }
        vm.openForgot()
        vm.password.setTextAndPlaceCursorAtEnd("wrong1")
        vm.submitForgot()
        settle()
        assertEquals(1, auth.passwordCalls.size)
        assertEquals(30, vm.state.value.forgotWait)
        vm.password.setTextAndPlaceCursorAtEnd("wrong2")
        vm.submitForgot()
        settle()
        assertEquals("nothing sent while waiting", 1, auth.passwordCalls.size)
        assertEquals("", vm.password.text.toString())
        assertEquals(30, vm.state.value.forgotWait)
        clock.ms += 30_000
        vm.password.setTextAndPlaceCursorAtEnd("wrong3")
        vm.submitForgot()
        settle()
        assertEquals(2, auth.passwordCalls.size)
        assertEquals(60, vm.state.value.forgotWait)
        assertTrue(locks.isProtected(adult))
    }

    /** Security review M8 F1: an empty field proves nothing (Jellyfin accepts "" for an account with no password). */
    @Test fun anEmptyForgotPasswordSendsNothingClearsNothingAndCostsNoWait() = runTest {
        val vm = vm()
        auth.onPassword = { id, u -> AccountSummary(id, U1, u) }
        vm.openForgot()
        vm.submitForgot()
        vm.password.setTextAndPlaceCursorAtEnd("   ")
        vm.submitForgot()
        settle()
        assertTrue("nothing sent", auth.passwordCalls.isEmpty())
        assertTrue(cleared.isEmpty())
        assertTrue(locks.isProtected(adult))
        assertTrue(vm.state.value.forgotNoPassword)
        assertNull(vm.state.value.forgotWait)
        assertTrue(gate.state.value is LockState.Profile)
        vm.password.setTextAndPlaceCursorAtEnd("hunter2")
        vm.submitForgot()
        settle()
        assertEquals("no wait was started by the empty tries", 1, auth.passwordCalls.size)
        assertFalse(vm.state.value.forgotNoPassword)
        assertEquals(LockState.Open, gate.state.value)
    }

    @Test fun anOfflineForgotTryCostsNoWait() = runTest {
        val vm = vm()
        auth.routeAnswer = { null }
        vm.openForgot()
        vm.password.setTextAndPlaceCursorAtEnd("hunter2")
        vm.submitForgot()
        settle()
        assertNull(vm.state.value.forgotWait)
        assertEquals(AppError.Kind.OFFLINE, vm.state.value.forgotError)
    }

    @Test fun aChangeOfLockClearsEveryFieldAndTheChoice() = runTest {
        val vm = vm(protectOther = true)
        vm.useProfile(vm.state.value.others.first { it.userName == "cat" })
        vm.pin.setTextAndPlaceCursorAtEnd("12")
        vm.choicePin.setTextAndPlaceCursorAtEnd("34")
        vm.password.setTextAndPlaceCursorAtEnd("pw")
        catalog.activeFlow.value = kidA // another path made the kid active: the profile lock opens
        settle()
        assertEquals(LockState.Open, gate.state.value)
        assertNull(vm.state.value.choosing)
        listOf(vm.pin, vm.choicePin, vm.password).forEach { assertEquals("", it.text.toString()) }
    }

    @Test fun theProofIsMadeOnlyForTheLockedProfile() {
        assertNull(ClearProof.afterPasswordSignIn(adult, AccountSummary(S1, U2, "ann")))
        assertNull(ClearProof.afterPasswordSignIn(adult, AccountSummary(S2, U1, "ann")))
        assertEquals(adult, ClearProof.afterPasswordSignIn(adult, adultA)?.session)
        assertEquals("ClearProof(<redacted>)", ClearProof.afterPasswordSignIn(adult, adultA).toString())
    }
}
