package app.shoalwatch.ui.admin

import app.shoalwatch.R
import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.core.AppError
import app.shoalwatch.data.admin.FULL_SCAN_TASK_KEY
import app.shoalwatch.data.admin.TaskState
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.address
import app.shoalwatch.ui.admin.AdminSamples.cleanCache
import app.shoalwatch.ui.admin.AdminSamples.films
import app.shoalwatch.ui.admin.AdminSamples.fullScanIdle
import app.shoalwatch.ui.admin.AdminSamples.tv
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibrariesViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val ben = AccountSummary(S1, U1, "ben")
    private val catalog = FakeCatalog(accounts = listOf(ben, AccountSummary(S1, U2, "kid")), active = ben)
    private val rights = FakeAdminRights()
    private val connections = FakeConnections().apply { stateFor(S1).value = Connection.Online(address("http://192.168.1.10:8096"), isHome = true) }
    private val admin = FakeAdmin().apply {
        libraries = listOf(films, tv)
        tasks = listOf(cleanCache, fullScanIdle)
    }

    private val models = ModelsInTest()
    private fun vm() = models.add(LibrariesViewModel(catalog, rights, connections, admin) { AdminSamples.now })
    private fun loads() = admin.calls.count { it == "libraries" }

    @Test fun idleLibrariesLoadOnceAndWaitForAKick() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        assertEquals(listOf("libraries", "tasks"), admin.calls)
        assertEquals(listOf(films, tv), vm.state.value.libraries)
        assertEquals(fullScanIdle, vm.state.value.fullScan)
        assertFalse(vm.state.value.anyScanning)
        advanceTimeBy(5 * 60_000L)
        assertEquals(1, loads())
        vm.refresh()
        assertEquals(2, loads())
        vm.setVisible(false)
    }

    @Test fun scanAllPollsEveryThreeSecondsUntilEveryLibraryIsIdle() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        admin.tasks = listOf(fullScanIdle.copy(state = TaskState.RUNNING, progressPercent = 45))
        vm.scanAll()
        assertEquals(1, admin.calls.count { it == "scanAll" })
        assertEquals(AdminNotice(R.string.admin_scan_started_all), vm.state.value.frame.notice)
        assertEquals("kicked after the scan started", 2, loads())
        assertTrue(vm.state.value.anyScanning)
        assertFalse("Scan all is off while the full scan runs", vm.state.value.canScanAll)
        advanceTimeBy(6_500)
        assertEquals("every 3 s while scanning", 4, loads())
        admin.tasks = listOf(fullScanIdle)
        advanceTimeBy(3_000)
        assertEquals(5, loads())
        assertFalse(vm.state.value.anyScanning)
        advanceTimeBy(5 * 60_000L)
        assertEquals("stops when all idle", 5, loads())
        vm.setVisible(false)
    }

    /** Round 3: the full scan going from running to not running tells Home its lists are due, once, as the gate's session. */
    @Test fun aFinishedFullScanMakesHomeDueOnceAndNotWhileItRuns() = main.adminTest(models) {
        val finished = mutableListOf<Session>()
        val vm = models.add(LibrariesViewModel(catalog, rights, connections, admin, finished::add) { AdminSamples.now })
        vm.setVisible(true)
        admin.tasks = listOf(fullScanIdle.copy(state = TaskState.RUNNING, progressPercent = 45))
        vm.scanAll()
        advanceTimeBy(6_500)
        assertEquals("not while it runs", emptyList<Session>(), finished)
        admin.tasks = listOf(fullScanIdle)
        advanceTimeBy(3_000)
        assertEquals(listOf(Session(S1, U1)), finished)
        vm.refresh(); advanceTimeBy(3_000)
        assertEquals("an idle scan read again is not another finish", 1, finished.size)
        vm.setVisible(false)
    }

    @Test fun aLibraryScanSendsThatIdOnly() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        admin.libraries = listOf(films.copy(scanning = true, progressPercent = 30), tv)
        vm.scanLibrary(films.id)
        assertEquals(listOf("scanLibrary:${films.id}"), admin.calls.filter { it.startsWith("scan") })
        assertEquals(AdminNotice(R.string.admin_scan_started, listOf("Films"), R.string.admin_untitled), vm.state.value.frame.notice)
        assertTrue(vm.state.value.anyScanning)
        advanceTimeBy(3_100)
        assertEquals("polling while Films scans", 3, loads())
        vm.scanLibrary("unknown")
        assertEquals(1, admin.calls.count { it.startsWith("scanLibrary") })
        vm.setVisible(false)
    }

    @Test fun enabledStateFollowsScanningAndCanAct() = main.adminTest(models) {
        admin.libraries = listOf(films.copy(scanning = true, progressPercent = 30), tv)
        val vm = vm()
        vm.setVisible(true)
        val state = vm.state.value
        assertTrue(state.anyScanning)
        assertTrue("the full scan is idle", state.canScanAll)
        assertFalse("Films is scanning", state.canScan(state.libraries[0]))
        assertTrue(state.canScan(state.libraries[1]))
        vm.scanLibrary(films.id)
        assertEquals("a library already scanning is not asked again", 0, admin.calls.count { it.startsWith("scanLibrary") })
        connections.stateFor(S1).value = Connection.Offline()
        val offline = vm.state.value
        assertFalse(offline.canScanAll)
        assertFalse(offline.canScan(offline.libraries[1]))
        vm.setVisible(false)
    }

    @Test fun aFailingTasksPartIsNotALoadProblem() = main.adminTest(models) {
        admin.failures["tasks"] = AppError.of(AppError.Kind.SERVER_ERROR)
        val vm = vm()
        vm.setVisible(true)
        assertNull(vm.state.value.fullScan)
        assertNull(vm.state.value.frame.loadProblem)
        assertEquals(listOf(films, tv), vm.state.value.libraries)
        assertTrue(vm.state.value.canScanAll)
        vm.setVisible(false)
    }

    @Test fun aFailedScanIsShownOnce() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        admin.failures["scanAll"] = AppError.of(AppError.Kind.NETWORK)
        vm.scanAll()
        advanceTimeBy(5 * 60_000L)
        assertEquals(AdminProblem.NETWORK, vm.state.value.frame.problem)
        assertEquals(1, admin.calls.count { it == "scanAll" })
        vm.setVisible(false)
    }

    @Test fun closedClearsTheLists() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        catalog.activeFlow.value = AccountSummary(S1, U2, "kid")
        val state = vm.state.value
        assertEquals(AdminGate.Closed, state.frame.gate)
        assertTrue(state.libraries.isEmpty())
        assertNull(state.fullScan)
        vm.scanAll()
        vm.scanLibrary(films.id)
        assertTrue(admin.calls.none { it.startsWith("scan") })
        assertTrue(admin.sessionsSeen.all { it == Session(S1, U1) })
    }

    @Test fun theFullScanIsFoundByItsKey() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        assertEquals(FULL_SCAN_TASK_KEY, vm.state.value.fullScan?.key)
        vm.setVisible(false)
    }
}
