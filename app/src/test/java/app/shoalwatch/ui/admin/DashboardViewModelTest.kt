package app.shoalwatch.ui.admin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.R
import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.core.AppError
import app.shoalwatch.data.admin.AdminStatus
import app.shoalwatch.data.admin.DashboardResult
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.address
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DashboardViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val ben = AccountSummary(S1, U1, "ben")
    private val kid = AccountSummary(S1, U2, "kid")
    private val owner = Session(S1, U1)
    private val catalog = FakeCatalog(accounts = listOf(ben, kid), active = ben)
    private val rights = FakeAdminRights()
    private val connections = FakeConnections().apply { stateFor(S1).value = Connection.Online(address("http://192.168.1.10:8096"), isHome = true) }
    private val admin = FakeAdmin()
    private val opener = FakeDashboardOpener()
    private val now = Instant.parse("2026-11-15T14:02:05Z")
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun vm() = DashboardViewModel(catalog, rights, connections, admin, opener) { now }

    @Test fun checkingUntilTheRightsAnswer() = runTest(main.dispatcher) {
        val answer = CompletableDeferred<AdminStatus>()
        rights.checkAnswer = { answer.await() }
        val vm = vm()
        vm.setVisible(true)
        assertEquals(AdminGate.Checking, vm.state.value.frame.gate)
        assertEquals(emptyList<String>(), admin.calls)
        answer.complete(AdminStatus.ADMIN)
        assertEquals(AdminGate.Open(owner, online = true), vm.state.value.frame.gate)
        vm.setVisible(false)
    }

    @Test fun openLoadsTheServerStatus() = runTest(main.dispatcher) {
        admin.status = admin.status.copy(pendingRestart = true)
        val vm = vm()
        vm.setVisible(true)
        val state = vm.state.value
        assertEquals(admin.status, state.status)
        assertTrue(state.status!!.pendingRestart)
        assertEquals(now, state.frame.updatedAt)
        assertFalse(state.frame.loading)
        assertEquals(listOf("serverStatus"), admin.calls)
        assertEquals(listOf(owner), admin.sessionsSeen)
        advanceTimeBy(SESSIONS_POLL_MS + 1)
        assertEquals("polled again after five seconds", 2, admin.calls.size)
        vm.setVisible(false)
    }

    @Test fun nothingLoadsUntilVisible() = runTest(main.dispatcher) {
        val vm = vm()
        advanceTimeBy(60_000)
        assertEquals(emptyList<String>(), admin.calls)
        assertNull(vm.state.value.status)
    }

    @Test fun aSwitchDropsEverythingAndStopsAtOnce() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setVisible(true)
        assertEquals(admin.status, vm.state.value.status)
        val calls = admin.calls.size
        catalog.activeFlow.value = kid
        assertEquals(AdminGate.Closed, vm.state.value.frame.gate)
        assertNull("nothing loaded is kept", vm.state.value.status)
        assertNull(vm.state.value.frame.updatedAt)
        advanceTimeBy(10 * 60_000L)
        assertEquals("no further calls", calls, admin.calls.size)
        vm.refresh()
        vm.openDashboard(context)
        assertEquals(calls, admin.calls.size)
        assertEquals(emptyList<Session>(), opener.opened)
    }

    @Test fun offlineLoadsNothing() = runTest(main.dispatcher) {
        connections.stateFor(S1).value = Connection.Offline()
        val vm = vm()
        vm.setVisible(true)
        advanceTimeBy(60_000)
        assertFalse(vm.state.value.frame.online)
        assertFalse(vm.state.value.frame.canAct)
        assertEquals(emptyList<String>(), admin.calls)
    }

    @Test fun theBrowserDashboardOpensAsTheGatesSession() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setVisible(true)
        vm.openDashboard(context)
        assertEquals(listOf(owner), opener.opened)
        assertNull(vm.state.value.frame.problem)
        assertNull(vm.state.value.frame.notice)
        opener.result = DashboardResult.OFFLINE
        vm.openDashboard(context)
        assertEquals(AdminProblem.OFFLINE, vm.state.value.frame.problem)
        opener.result = DashboardResult.NO_BROWSER
        vm.openDashboard(context)
        assertEquals(AdminNotice(R.string.admin_no_browser), vm.state.value.frame.notice)
        vm.dismissNotice()
        assertNull(vm.state.value.frame.notice)
        vm.setVisible(false)
    }

    @Test fun aLoadFailureIsShownAndTheLastStatusKept() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setVisible(true)
        val shown = vm.state.value.status
        admin.failures["serverStatus"] = AppError.of(AppError.Kind.NETWORK)
        vm.refresh()
        assertEquals(AdminProblem.NETWORK, vm.state.value.frame.loadProblem)
        assertEquals(shown, vm.state.value.status)
        admin.failures.clear()
        vm.refresh()
        assertNull(vm.state.value.frame.loadProblem)
        vm.setVisible(false)
    }

    @Test fun aRefusalThatEndsTheRightsClosesWithoutAWord() = runTest(main.dispatcher) {
        val vm = vm()
        vm.setVisible(true)
        admin.failures["serverStatus"] = AppError.of(AppError.Kind.FORBIDDEN)
        rights.answer = AdminStatus.NOT_ADMIN
        vm.refresh()
        assertEquals(AdminGate.Closed, vm.state.value.frame.gate)
        assertNull(vm.state.value.frame.loadProblem)
        assertNull(vm.state.value.status)
    }
}
