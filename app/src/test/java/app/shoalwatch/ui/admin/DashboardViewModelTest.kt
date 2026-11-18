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
import app.shoalwatch.data.admin.Admin
import app.shoalwatch.data.admin.ServerStatus
import app.shoalwatch.data.admin.WatchSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import app.shoalwatch.data.admin.TaskState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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

    private val models = ModelsInTest()
    private fun vm() = models.add(DashboardViewModel(catalog, rights, connections, admin, opener) { now })

    @Test fun checkingUntilTheRightsAnswer() = main.adminTest(models) {
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

    @Test fun openLoadsTheServerStatus() = main.adminTest(models) {
        admin.status = admin.status.copy(pendingRestart = true)
        val vm = vm()
        vm.setVisible(true)
        val state = vm.state.value
        assertEquals(admin.status, state.status)
        assertTrue(state.status!!.pendingRestart)
        assertEquals(now, state.frame.updatedAt)
        assertFalse(state.frame.loading)
        assertEquals(listOf("serverStatus", "sessions", "tasks"), admin.calls)
        assertEquals(listOf(owner, owner, owner), admin.sessionsSeen)
        advanceTimeBy(SESSIONS_POLL_MS + 1)
        assertEquals("polled again after five seconds", 6, admin.calls.size)
        vm.setVisible(false)
    }

    @Test fun nothingLoadsUntilVisible() = main.adminTest(models) {
        val vm = vm()
        advanceTimeBy(60_000)
        assertEquals(emptyList<String>(), admin.calls)
        assertNull(vm.state.value.status)
    }

    @Test fun aSwitchDropsEverythingAndStopsAtOnce() = main.adminTest(models) {
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

    @Test fun offlineLoadsNothing() = main.adminTest(models) {
        connections.stateFor(S1).value = Connection.Offline()
        val vm = vm()
        vm.setVisible(true)
        advanceTimeBy(60_000)
        assertFalse(vm.state.value.frame.online)
        assertFalse(vm.state.value.frame.canAct)
        assertEquals(emptyList<String>(), admin.calls)
    }

    /** The job can wait for the address: it must hold the application, never the screen's Activity (a rotation would leak it). */
    @Test fun theBrowserDashboardIsOpenedFromTheApplicationNotTheScreen() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        val screen = android.content.ContextWrapper(context)
        vm.openDashboard(screen)
        assertSame(context.applicationContext, opener.contexts.single())
        vm.setVisible(false)
    }

    @Test fun theBrowserDashboardOpensAsTheGatesSession() = main.adminTest(models) {
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

    @Test fun aLoadFailureIsShownAndTheLastStatusKept() = main.adminTest(models) {
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

    @Test fun aRefusalThatEndsTheRightsClosesWithoutAWord() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        admin.failures["serverStatus"] = AppError.of(AppError.Kind.FORBIDDEN)
        rights.answer = AdminStatus.NOT_ADMIN
        vm.refresh()
        assertEquals(AdminGate.Closed, vm.state.value.frame.gate)
        assertNull(vm.state.value.frame.loadProblem)
        assertNull(vm.state.value.status)
    }

    /**
     * An answer that lands after the gate closed (here: one that cannot be cancelled) is not written back into the ViewModel:
     * nothing the server said outlives the session in memory, not just on screen.
     */
    @Test fun anAnswerArrivingAfterTheGateClosedIsNotKept() = main.adminTest(models) {
        val slow = CompletableDeferred<Unit>()
        val late = object : Admin by admin {
            override suspend fun serverStatus(s: Session): ServerStatus = withContext(NonCancellable) { slow.await(); admin.status }
        }
        admin.sessions = listOf(AdminSamples.kidOnTv)
        val vm = models.add(DashboardViewModel(catalog, rights, connections, late, opener) { now })
        vm.setVisible(true)
        catalog.activeFlow.value = kid
        assertEquals(AdminGate.Closed, vm.state.value.frame.gate)
        slow.complete(Unit)
        assertNull("status kept after the close", held<ServerStatus?>(vm, "status"))
        assertEquals("sessions kept after the close", emptyList<WatchSession>(), held<List<WatchSession>>(vm, "sessions"))
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> held(vm: DashboardViewModel, field: String): T =
        (DashboardViewModel::class.java.getDeclaredField(field).apply { isAccessible = true }.get(vm) as MutableStateFlow<T>).value

    // ---- 1.0.1: "Scan all libraries" at the top of Server admin (the same rule as the Libraries screen: `ScanAll`)

    @Test fun scanAllStartsTheSameScanAsTheLibrariesScreen() = main.adminTest(models) {
        admin.tasks = listOf(AdminSamples.cleanCache, AdminSamples.fullScanIdle)
        val vm = vm()
        vm.setVisible(true)
        assertEquals(AdminSamples.fullScanIdle, vm.state.value.fullScan)
        assertTrue(vm.state.value.canScanAll)
        admin.tasks = listOf(AdminSamples.fullScanIdle.copy(state = TaskState.RUNNING, progressPercent = 45))
        vm.scanAll()
        assertEquals(listOf(owner), admin.sessionsSeen.filterIndexed { i, _ -> admin.calls[i] == "scanAll" })
        assertEquals(AdminNotice(R.string.admin_scan_started_all), vm.state.value.frame.notice)
        assertEquals("reloaded after the scan started", 45, vm.state.value.fullScan?.progressPercent)
        assertFalse("off while the full scan runs", vm.state.value.canScanAll)
        vm.scanAll()
        assertEquals("not asked again while it runs", 1, admin.calls.count { it == "scanAll" })
        vm.setVisible(false)
    }

    @Test fun scanAllIsOffOfflineAndAFailureIsShownOnce() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        admin.failures["scanAll"] = AppError.of(AppError.Kind.NETWORK)
        vm.scanAll()
        advanceTimeBy(60_000)
        assertEquals(AdminProblem.NETWORK, vm.state.value.frame.problem)
        assertEquals(1, admin.calls.count { it == "scanAll" })
        connections.stateFor(S1).value = Connection.Offline()
        assertFalse(vm.state.value.canScanAll)
        vm.setVisible(false)
    }

    @Test fun aFailingTasksPartIsNotALoadProblemOnTheDashboard() = main.adminTest(models) {
        admin.failures["tasks"] = AppError.of(AppError.Kind.SERVER_ERROR)
        val vm = vm()
        vm.setVisible(true)
        assertNull(vm.state.value.fullScan)
        assertNull(vm.state.value.frame.loadProblem)
        assertEquals(admin.status, vm.state.value.status)
        assertTrue(vm.state.value.canScanAll)
        vm.setVisible(false)
    }

    @Test fun aClosedGateDropsTheScanAndSendsNothing() = main.adminTest(models) {
        admin.tasks = listOf(AdminSamples.fullScanIdle)
        val vm = vm()
        vm.setVisible(true)
        catalog.activeFlow.value = kid
        assertEquals(AdminGate.Closed, vm.state.value.frame.gate)
        assertNull(vm.state.value.fullScan)
        assertFalse(vm.state.value.canScanAll)
        vm.scanAll()
        assertEquals(0, admin.calls.count { it == "scanAll" })
    }

    @Test fun aSignInThatEndsWhileReadingTheScanClosesTheGate() = main.adminTest(models) {
        admin.failures["tasks"] = AppError.of(AppError.Kind.UNAUTHORISED)
        val vm = vm()
        vm.setVisible(true)
        assertEquals("a lost sign-in while reading the scan still closes the gate", AdminGate.Closed, vm.state.value.frame.gate)
        assertNull(vm.state.value.status)
        vm.scanAll()
        assertEquals(0, admin.calls.count { it == "scanAll" })
    }
}
