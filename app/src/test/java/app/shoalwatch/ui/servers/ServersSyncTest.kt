package app.shoalwatch.ui.servers

import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.sync.SyncStatus
import app.shoalwatch.ui.FakeAuthenticator
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.browse.FakeSync
import app.shoalwatch.ui.testServer
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Servers and accounts with the outbox: each profile's own line, and sign-out and removal send what they can first, for 10 s at most. */
class ServersSyncTest {
    @get:Rule val main = MainDispatcherRule()

    private val ben = AccountSummary("s1", "u1", "ben")
    private val amy = AccountSummary("s1", "u2", "amy")
    private val cal = AccountSummary("s2", "u3", "cal")
    private val catalog = FakeCatalog(listOf(testServer("s1"), testServer("s2")), listOf(ben, amy, cal), ben)
    private val log = mutableListOf<String>()
    private val auth = FakeAuthenticator(catalog).apply { onSignOut = { s, u -> log += "signOut:$s/$u" } }
    private val sync = FakeSync().apply { onSendNow = { s -> log += "send:${s.serverId}/${s.userId}" } }
    private val bens = Session("s1", "u1")
    private val amys = Session("s1", "u2")

    private fun vm() = ServersViewModel(catalog, auth, sync = sync)

    @Test fun eachProfileShowsItsOwnStatusOnly() {
        sync.set(bens, waiting = 2)
        sync.set(amys, waiting = 1, stuck = 1)
        val vm = vm()
        val accounts = vm.state.value.servers.first { it.id == "s1" }.accounts.associateBy { it.userName }
        assertEquals(2, accounts.getValue("ben").sync.waiting)
        assertEquals(SyncStatus(1, 1, false, null), accounts.getValue("amy").sync)
        assertEquals(SyncStatus.NONE, vm.state.value.servers.first { it.id == "s2" }.accounts.single().sync)
    }

    @Test fun signOutSendsAsThatProfileFirstThenSignsOut() = runTest(main.dispatcher) {
        sync.set(amys, waiting = 3)
        val vm = vm()
        vm.askSignOut("s1", "u2")
        runCurrent()
        vm.confirm()
        runCurrent()
        assertEquals(listOf("send:s1/u2", "signOut:s1/u2"), log)
        assertEquals("its own session, 10 s", listOf(amys to 10_000L), sync.sent)
        assertFalse(vm.state.value.busy)
        assertNull(vm.state.value.confirm)
    }

    @Test fun signOutGoesAheadAfterTenSecondsEvenIfTheSendNeverReturns() = runTest(main.dispatcher) {
        sync.onSendNow = { log += "send"; awaitCancellation() }
        sync.set(amys, waiting = 1)
        val vm = vm()
        vm.askSignOut("s1", "u2")
        runCurrent()
        vm.confirm()
        runCurrent()
        assertTrue("sending, with the button's spinner", vm.state.value.sending)
        advanceTimeBy(9_999)
        assertTrue("not yet", auth.signedOut.isEmpty())
        advanceTimeBy(2)
        assertEquals(listOf("s1" to "u2"), auth.signedOut)
        assertEquals(10_001L, currentTime)
        assertFalse(vm.state.value.sending)
    }

    /** Tasks 9-10 review M6: while it sends, Cancel and Back do nothing, so the dialog never says "cancelled" over a sign-out that still happens. */
    @Test fun whileSendingCancelDoesNotCloseTheDialog() = runTest(main.dispatcher) {
        sync.onSendNow = { awaitCancellation() }
        sync.set(amys, waiting = 1)
        val vm = vm()
        vm.askSignOut("s1", "u2")
        runCurrent()
        vm.confirm()
        runCurrent()
        vm.dismissConfirm()
        assertTrue("still asked while sending", vm.state.value.confirm is Confirm.SignOut)
        advanceTimeBy(10_001)
        assertEquals(listOf("s1" to "u2"), auth.signedOut)
    }

    @Test fun theSignOutDialogShowsTheCountOnlyWhenThereIsOne() = runTest(main.dispatcher) {
        sync.set(amys, waiting = 4)
        val vm = vm()
        vm.askSignOut("s1", "u2"); runCurrent()
        assertEquals(4, (vm.state.value.confirm as Confirm.SignOut).waiting)
        vm.dismissConfirm()
        vm.askSignOut("s1", "u1"); runCurrent()
        assertEquals(0, (vm.state.value.confirm as Confirm.SignOut).waiting)
    }

    @Test fun removingAServerSendsForEachOfItsProfilesInParallelWithinTenSecondsInAll() = runTest(main.dispatcher) {
        sync.onSendNow = { s -> log += "send:${s.userId}"; awaitCancellation() }
        sync.set(bens, waiting = 1)
        sync.set(amys, waiting = 2)
        val vm = vm()
        vm.askRemoveServer("s1"); runCurrent()
        assertEquals("the dialog counts every profile's", 3, (vm.state.value.confirm as Confirm.RemoveServer).waiting)
        vm.confirm(); runCurrent()
        assertEquals("both at once", setOf("send:u1", "send:u2"), log.toSet())
        assertTrue(auth.removedServers.isEmpty())
        advanceTimeBy(10_001)
        assertEquals(listOf("s1"), auth.removedServers)
        assertEquals("never another server's profile", setOf(bens, amys), sync.sent.map { it.first }.toSet())
    }

    // ---- Tasks 9-10 review I2: a profile whose sign-in the server rejected

    private val dan = AccountSummary("s1", "u4", "dan")
    private val dans = Session("s1", "u4")

    @Test fun aRejectedProfileIsListedAsExpiredWithItsOwnLineAndCannotBeSwitchedTo() = runTest(main.dispatcher) {
        catalog.expiredList.value = listOf(dan)
        sync.set(dans, waiting = 6, needsSignIn = true)
        val vm = vm()
        runCurrent()
        val row = vm.state.value.servers.first { it.id == "s1" }.accounts.single { it.userName == "dan" }
        assertTrue(row.expired)
        assertFalse(row.isActive)
        assertEquals(SyncStatus(6, 0, true, null), row.sync)
        vm.switchTo("s1", "u4")
        runCurrent()
        assertTrue("never made active without signing in", catalog.activated.isEmpty())
    }

    @Test fun removingAServerCountsAndNamesEveryProfilesChangesIncludingAnExpiredOne() = runTest(main.dispatcher) {
        catalog.expiredList.value = listOf(dan)
        sync.set(bens, waiting = 1)
        sync.set(dans, waiting = 6, needsSignIn = true)
        val vm = vm()
        runCurrent()
        vm.askRemoveServer("s1"); runCurrent()
        val ask = vm.state.value.confirm as Confirm.RemoveServer
        assertEquals(7, ask.waiting)
        assertEquals(listOf(ProfileWaiting("ben", 1, expired = false), ProfileWaiting("dan", 6, expired = true)), ask.profiles)
        assertTrue("dan's sign-in goes with the server too", "dan" in ask.accountNames)
        vm.confirm(); runCurrent()
        advanceTimeBy(10_001)
        assertEquals("an expired profile cannot send first: never sent as", setOf(bens, amys), sync.sent.map { it.first }.toSet())
    }

    @Test fun withoutTheSyncSeamSignOutIsAsBefore() = runTest(main.dispatcher) {
        val vm = ServersViewModel(catalog, auth)
        vm.askSignOut("s1", "u2"); runCurrent()
        vm.confirm(); runCurrent()
        assertEquals(listOf("signOut:s1/u2"), log)
    }
}
