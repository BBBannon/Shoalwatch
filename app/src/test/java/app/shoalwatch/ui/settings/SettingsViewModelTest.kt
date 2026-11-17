package app.shoalwatch.ui.settings

import android.content.Context
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.data.playback.FakeProfileDao
import app.shoalwatch.data.playback.ProfilePlayback
import app.shoalwatch.data.servers.Session
import app.shoalwatch.security.HouseholdLockedException
import app.shoalwatch.security.LockAfter
import app.shoalwatch.security.LockPrefs
import app.shoalwatch.security.PinCheck
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.SERVER_ID
import app.shoalwatch.ui.browse.account
import app.shoalwatch.ui.lock.FakeHousehold
import app.shoalwatch.ui.lock.FakeProfileLocks
import app.shoalwatch.ui.testServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Settings screen's ViewModel (Milestone 7 Task 7): autoplay for the active profile only, the PIN steps (protect, change,
 * remove) with the field cleared after every step, and the grown-up check before the app lock, "Lock after", "Hide in recent apps",
 * and before protect, change and remove while ANOTHER profile is protected (review I1 of Tasks 3-4).
 */
@RunWith(RobolectricTestRunner::class)
class SettingsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val adult = account(AMY, "amy")
    private val catalog = FakeCatalog(listOf(testServer(id = SERVER_ID)), listOf(account(BEN, "ben"), adult), account(BEN, "ben"))
    private val dao = FakeProfileDao()
    private val locks = FakeProfileLocks()
    private val household = FakeHousehold(ask = false, grownUps = listOf(adult))
    /** The store's own household check (as `LockParts` wires `HouseholdGuard.require`), refusing on demand. */
    private var storeRefuses = false
    /** The store's household check failing to read (Room: the disk full, the database broken). */
    private var storeFails: Exception? = null
    private val prefs = LockPrefs(
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("settings_vm_test", Context.MODE_PRIVATE).also { it.edit().clear().commit() },
    ) {
        storeFails?.let { throw it }
        if (storeRefuses) throw HouseholdLockedException()
    }
    private var secure = true
    private val hideRecents = MutableStateFlow(false)

    private fun vm() = SettingsViewModel(catalog, ProfilePlayback(dao), locks, prefs, household, { secure }, hideRecents)

    private fun SettingsViewModel.type(pin: String) {
        this.pin.setTextAndPlaceCursorAtEnd(pin)
        submitPin()
        assertEquals("the field is empty after every submit", "", this.pin.text.toString())
    }

    /** Another profile is protected: the household asks a grown-up (as `HouseholdGuard.askNeeded` does then). */
    private fun anotherIsProtected() {
        locks.pins.value = mapOf(AMY to "4826")
        household.ask.value = true
    }

    private fun SettingsViewModel.grownUpSays(pin: String) {
        grownUp.pin.setTextAndPlaceCursorAtEnd(pin)
        grownUp.submit()
    }

    // ---- autoplay

    @Test fun autoplayIsOnAndWritesForTheActiveProfileOnly() = runBlocking {
        val vm = vm()
        assertTrue(vm.state.value.autoplay)
        vm.setAutoplay(false)
        assertFalse(vm.state.value.autoplay)
        assertFalse(ProfilePlayback(dao).autoplay(BEN).first())
        assertTrue("another profile keeps its own", ProfilePlayback(dao).autoplay(AMY).first())
        assertEquals("ben", vm.state.value.userName)
    }

    // ---- protect (the first PIN on the phone: no grown-up)

    @Test fun protectAsksTwiceThenProtectsOnce() {
        val vm = vm()
        vm.startProtect()
        assertNull("no grown-up check with nobody protected", vm.grownUp.ask.value)
        assertEquals(PinStep.NEW, vm.state.value.pinStep)
        vm.type("2468")
        assertEquals(PinStep.REPEAT, vm.state.value.pinStep)
        assertEquals(emptyList<String>(), locks.calls)
        vm.type("2468")
        assertEquals(listOf("protect:${BEN.userId}:2468"), locks.calls)
        assertNull(vm.state.value.pinStep)
        assertTrue(vm.state.value.profileProtected)
        assertTrue("every array is zeroed", locks.arrays.all { a -> a.all { it == '\u0000' } })
    }

    @Test fun aMismatchStartsAgainAtNewAndCallsNothing() {
        val vm = vm()
        vm.startProtect()
        vm.type("2468")
        vm.type("2469")
        assertEquals(PinStep.NEW, vm.state.value.pinStep)
        assertTrue(vm.state.value.pinMismatch)
        assertEquals(emptyList<String>(), locks.calls)
        vm.type("1357")
        assertFalse("the note goes on the next step", vm.state.value.pinMismatch)
        vm.type("1357")
        assertEquals(listOf("protect:${BEN.userId}:1357"), locks.calls)
    }

    @Test fun aPinOfUnderFourDigitsIsNotAccepted() {
        val vm = vm()
        vm.startProtect()
        vm.type("123")
        assertEquals(PinStep.NEW, vm.state.value.pinStep)
        assertTrue(vm.state.value.pinTooShort)
        assertEquals(emptyList<String>(), locks.calls)
    }

    @Test fun cancelForgetsTheStepAndTheField() {
        val vm = vm()
        vm.startProtect()
        vm.type("2468")
        vm.pin.setTextAndPlaceCursorAtEnd("24")
        vm.cancelPin()
        assertNull(vm.state.value.pinStep)
        assertEquals("", vm.pin.text.toString())
        vm.startProtect()
        assertEquals("starts again from the first PIN", PinStep.NEW, vm.state.value.pinStep)
    }

    // ---- change and remove

    @Test fun changeAsksTheCurrentPinFirstAndAWrongOneStays() {
        locks.pins.value = mapOf(BEN to "2468")
        val vm = vm()
        assertTrue(vm.state.value.profileProtected)
        vm.startChangePin()
        assertEquals(PinStep.CURRENT_FOR_CHANGE, vm.state.value.pinStep)
        vm.type("1111")
        assertEquals(PinStep.CURRENT_FOR_CHANGE, vm.state.value.pinStep)
        assertEquals(PinCheck.Wrong(4), vm.state.value.pinAnswer)
        vm.type("2468")
        assertEquals(PinStep.NEW_FOR_CHANGE, vm.state.value.pinStep)
        assertNull(vm.state.value.pinAnswer)
        vm.type("9753")
        assertEquals(PinStep.REPEAT_FOR_CHANGE, vm.state.value.pinStep)
        vm.type("9753")
        assertNull(vm.state.value.pinStep)
        assertEquals(
            listOf("check:${BEN.userId}:1111", "check:${BEN.userId}:2468", "changePin:${BEN.userId}:2468:9753"),
            locks.calls,
        )
        assertEquals("9753", locks.pins.value[BEN])
        assertTrue(locks.arrays.all { a -> a.all { it == '\u0000' } })
    }

    @Test fun aMismatchWhileChangingGoesBackToTheNewPinAndKeepsTheCurrentOne() {
        locks.pins.value = mapOf(BEN to "2468")
        val vm = vm()
        vm.startChangePin()
        vm.type("2468")
        vm.type("9753")
        vm.type("9750")
        assertEquals(PinStep.NEW_FOR_CHANGE, vm.state.value.pinStep)
        assertTrue(vm.state.value.pinMismatch)
        vm.type("1357"); vm.type("1357")
        assertEquals("changePin:${BEN.userId}:2468:1357", locks.calls.last())
    }

    @Test fun removeWithTheRightPinUnprotects() {
        locks.pins.value = mapOf(BEN to "2468")
        val vm = vm()
        vm.startRemovePin()
        assertEquals(PinStep.CURRENT_FOR_REMOVE, vm.state.value.pinStep)
        vm.type("1111")
        assertEquals(PinStep.CURRENT_FOR_REMOVE, vm.state.value.pinStep)
        assertEquals(PinCheck.Wrong(4), vm.state.value.pinAnswer)
        vm.type("2468")
        assertNull(vm.state.value.pinStep)
        assertFalse(vm.state.value.profileProtected)
        assertEquals(listOf("unprotect:${BEN.userId}:1111", "unprotect:${BEN.userId}:2468"), locks.calls)
    }

    @Test fun aTryThatCannotBeSavedIsNeverRight() {
        locks.pins.value = mapOf(BEN to "2468")
        locks.failure = IllegalStateException("disk full")
        val vm = vm()
        vm.startRemovePin()
        vm.type("2468")
        assertEquals(PinStep.CURRENT_FOR_REMOVE, vm.state.value.pinStep)
        assertTrue(vm.state.value.pinFailed)
        assertTrue(vm.state.value.profileProtected)
    }

    // ---- protect, change and remove while ANOTHER profile is protected: a grown-up first (controller decision, review I1)

    @Test fun withAnotherProfileProtectedProtectingAsksAGrownUpFirst() {
        anotherIsProtected()
        val vm = vm()
        vm.startProtect()
        assertNotNull("the grown-up check comes first", vm.grownUp.ask.value)
        assertNull(vm.state.value.pinStep)
        vm.grownUpSays("1111")
        assertNull("a wrong grown-up PIN starts nothing", vm.state.value.pinStep)
        vm.grownUpSays("4826")
        assertEquals(PinStep.NEW, vm.state.value.pinStep)
        vm.type("2468"); vm.type("2468")
        assertEquals(listOf("protect:${BEN.userId}:2468"), locks.calls)
    }

    @Test fun withAnotherProfileProtectedChangeAndRemoveAskAGrownUpFirstAndDismissStartsNothing() {
        anotherIsProtected()
        locks.pins.value = locks.pins.value + (BEN to "2468")
        val vm = vm()
        vm.startChangePin()
        assertNotNull(vm.grownUp.ask.value)
        vm.grownUp.dismiss()
        assertNull(vm.state.value.pinStep)
        vm.startRemovePin()
        assertNotNull(vm.grownUp.ask.value)
        vm.grownUpSays("4826")
        assertEquals(PinStep.CURRENT_FOR_REMOVE, vm.state.value.pinStep)
        assertEquals(emptyList<String>(), locks.calls)
    }

    @Test fun aRefusalFromTheDataLayerShowsTheRaceLineAndEndsTheSteps() {
        val vm = vm()
        vm.startProtect()
        vm.type("2468")
        locks.failure = HouseholdLockedException()
        vm.type("2468")
        assertNull(vm.state.value.pinStep)
        assertTrue(vm.state.value.householdRace)
        assertFalse(vm.state.value.profileProtected)
    }

    // ---- the device's lock settings: a grown-up's PIN first when one is needed

    @Test fun theAppLockLockAfterAndHideInRecentsSaveOnlyAfterTheGrownUpAllows() {
        anotherIsProtected()
        val vm = vm()
        vm.setAppLock(true)
        assertFalse(prefs.settings.value.appLock)
        vm.grownUpSays("4826")
        assertTrue(prefs.settings.value.appLock)
        vm.setLockAfter(LockAfter.IMMEDIATELY)
        assertEquals(LockAfter.ONE_MINUTE, prefs.settings.value.lockAfter)
        vm.grownUp.dismiss()
        assertEquals("dismissed: nothing saved", LockAfter.ONE_MINUTE, prefs.settings.value.lockAfter)
        vm.setLockAfter(LockAfter.IMMEDIATELY)
        vm.grownUpSays("4826")
        assertEquals(LockAfter.IMMEDIATELY, prefs.settings.value.lockAfter)
        vm.setHideInRecents(true)
        assertFalse(prefs.settings.value.hideInRecents)
        vm.grownUpSays("4826")
        assertTrue(prefs.settings.value.hideInRecents)
        assertEquals(LockAfter.IMMEDIATELY, vm.state.value.lock.lockAfter)
    }

    /** Review I1 of Tasks 7-8: the screen's check passed, but the store refuses (the pass ran out): nothing saved, the race line. */
    @Test fun aRefusalFromTheStoreSavesNoLockSettingAndShowsTheRaceLine() {
        storeRefuses = true
        val vm = vm()
        vm.setAppLock(true)
        vm.setLockAfter(LockAfter.FIFTEEN_MINUTES)
        vm.setHideInRecents(true)
        assertEquals("nothing saved", app.shoalwatch.security.LockSettings(), prefs.settings.value)
        assertTrue(vm.state.value.householdRace)
    }

    /** Re-review N2: the store's household check cannot read (the disk full): nothing saved, the "not saved" line, no crash. */
    @Test fun aFailedReadInTheStoresCheckSavesNothingAndSaysSo() {
        storeFails = android.database.sqlite.SQLiteFullException("database or disk is full")
        val vm = vm()
        vm.setAppLock(true)
        vm.setLockAfter(LockAfter.FIFTEEN_MINUTES)
        vm.setHideInRecents(true)
        assertEquals("nothing saved", app.shoalwatch.security.LockSettings(), prefs.settings.value)
        assertTrue(vm.state.value.notSaved)
        assertFalse("not the race line", vm.state.value.householdRace)
        storeFails = null
        vm.setLockAfter(LockAfter.FIFTEEN_MINUTES)
        assertEquals(LockAfter.FIFTEEN_MINUTES, prefs.settings.value.lockAfter)
        assertFalse("a later save clears it", vm.state.value.notSaved)
    }

    /** Review M1 of Tasks 7-8: Cancel while the current PIN is being checked stays cancelled, whatever the check says. */
    @Test fun cancellingWhileTheCurrentPinIsCheckedIsNotUndone() {
        locks.pins.value = mapOf(BEN to "2468")
        val vm = vm()
        for ((start, typed) in listOf<Pair<() -> Unit, String>>({ vm.startChangePin() } to "2468", { vm.startChangePin() } to "1111", { vm.startRemovePin() } to "1111")) {
            start()
            val hold = kotlinx.coroutines.CompletableDeferred<Unit>()
            locks.hold = hold
            vm.type(typed)
            vm.cancelPin()
            hold.complete(Unit)
            assertNull("Cancel stays cancelled ($typed)", vm.state.value.pinStep)
            assertNull(vm.state.value.pinAnswer)
            locks.hold = null
        }
        vm.startChangePin()
        vm.type("2468")
        assertEquals("not cancelled: the next step", PinStep.NEW_FOR_CHANGE, vm.state.value.pinStep)
    }

    /** Review M4 of Tasks 7-8: a failed autoplay write, and a PIN that was not set, say so. */
    @Test fun aChangeThatWasNotSavedSaysSo() {
        val vm = vm()
        dao.failure = android.database.sqlite.SQLiteFullException("database or disk is full")
        vm.setAutoplay(false)
        assertTrue("autoplay", vm.state.value.notSaved)
        assertTrue("the switch shows what is stored", vm.state.value.autoplay)
        dao.failure = null
        vm.setAutoplay(false)
        assertFalse("a later save clears it (re-review nit)", vm.state.value.notSaved)
        assertFalse(vm.state.value.autoplay)
        vm.startProtect()
        assertFalse("a new step clears it", vm.state.value.notSaved)
        vm.type("2468")
        locks.pins.value = mapOf(BEN to "1357") // protected meanwhile, elsewhere: protect says false
        vm.type("2468")
        assertNull(vm.state.value.pinStep)
        assertTrue("protect said no", vm.state.value.notSaved)
    }

    @Test fun withNobodyProtectedTheLockSettingsSaveAtOnce() {
        val vm = vm()
        vm.setAppLock(true)
        assertNull(vm.grownUp.ask.value)
        assertTrue(prefs.settings.value.appLock)
        vm.setAppLock(false)
        assertFalse(prefs.settings.value.appLock)
    }

    @Test fun theAppLockCannotBeTurnedOnWithoutAScreenLock() {
        secure = false
        val vm = vm()
        assertFalse(vm.state.value.deviceSecure)
        vm.setAppLock(true)
        assertFalse(prefs.settings.value.appLock)
        secure = true
        vm.refreshDevice()
        assertTrue(vm.state.value.deviceSecure)
    }

    @Test fun recentsForcedFollowsHideRecents() {
        val vm = vm()
        assertFalse(vm.state.value.recentsForced)
        hideRecents.value = true
        assertTrue(vm.state.value.recentsForced)
        vm.setHideInRecents(true)
        assertFalse("a forced switch saves nothing", prefs.settings.value.hideInRecents)
        hideRecents.value = false
        assertFalse(vm.state.value.recentsForced)
    }

    @Test fun withOnlyTheSwitchOnRecentsAreNotForced() {
        val vm = vm()
        vm.setHideInRecents(true)
        hideRecents.value = true // what LockGate says because of the switch itself
        assertFalse(vm.state.value.recentsForced)
        vm.setHideInRecents(false)
        assertFalse(prefs.settings.value.hideInRecents)
    }

    // ---- the session rule

    @Test fun aSessionChangeEndsTheStepsAndClearsTheField() {
        val vm = vm()
        vm.startProtect()
        vm.type("2468")
        vm.pin.setTextAndPlaceCursorAtEnd("24")
        catalog.activeFlow.value = adult
        assertNull(vm.state.value.pinStep)
        assertEquals("", vm.pin.text.toString())
        assertEquals("amy", vm.state.value.userName)
        vm.setAutoplay(false)
        runBlocking { assertFalse(ProfilePlayback(dao).autoplay(AMY).first()) }
        runBlocking { assertTrue("ben's setting is untouched", ProfilePlayback(dao).autoplay(BEN).first()) }
    }

    @Test fun theStateNeverSaysThePin() {
        val vm = vm()
        vm.startProtect()
        vm.pin.setTextAndPlaceCursorAtEnd("2468")
        assertEquals("SettingsUiState(<redacted>)", vm.state.value.toString())
        assertFalse(vm.state.value.toString().contains("2468"))
        assertEquals(Session(BEN.serverId, BEN.userId), vm.state.value.session)
    }
}
