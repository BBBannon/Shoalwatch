package app.shoalwatch.ui.admin

import app.shoalwatch.R
import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.core.AppError
import app.shoalwatch.data.admin.TaskState
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.address
import app.shoalwatch.ui.admin.AdminSamples.cleanCache
import app.shoalwatch.ui.admin.AdminSamples.failedTask
import app.shoalwatch.ui.admin.AdminSamples.fullScanIdle
import app.shoalwatch.ui.admin.AdminSamples.uncategorised
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TasksViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val ben = AccountSummary(S1, U1, "ben")
    private val catalog = FakeCatalog(accounts = listOf(ben, AccountSummary(S1, U2, "kid")), active = ben)
    private val rights = FakeAdminRights()
    private val connections = FakeConnections().apply { stateFor(S1).value = Connection.Online(address("http://192.168.1.10:8096"), isHome = true) }
    private val running = fullScanIdle.copy(state = TaskState.RUNNING, progressPercent = 40)
    private val admin = FakeAdmin().apply { tasks = listOf(uncategorised, fullScanIdle, cleanCache, failedTask) }

    private val models = ModelsInTest()
    private fun vm() = models.add(TasksViewModel(catalog, rights, connections, admin) { AdminSamples.now })
    private fun loads() = admin.calls.count { it == "tasks" }

    @Test fun groupedByCategoryInAlphabeticalOrderWithOtherLast() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        assertEquals(
            listOf(TaskGroup("Library", listOf(fullScanIdle, failedTask)), TaskGroup("Maintenance", listOf(cleanCache)), TaskGroup("", listOf(uncategorised))),
            vm.state.value.groups,
        )
        vm.setVisible(false)
    }

    @Test fun pollsFastWhileATaskRunsSlowWhenIdleAndNeverWhileHidden() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        advanceTimeBy(30_500)
        assertEquals("at 0 and 30 s while idle", 2, loads())
        admin.tasks = listOf(running, cleanCache)
        vm.refresh()
        assertEquals(3, loads())
        advanceTimeBy(6_500)
        assertEquals("every 3 s while running", 5, loads())
        admin.tasks = listOf(running.copy(state = TaskState.CANCELLING), cleanCache)
        advanceTimeBy(3_000)
        assertEquals("cancelling counts as busy", 6, loads())
        vm.setVisible(false)
        advanceTimeBy(10 * 60_000L)
        assertEquals("none while hidden", 6, loads())
    }

    @Test fun runNowSendsOnceAndKicks() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        vm.run(cleanCache.id)
        assertEquals(listOf("startTask:${cleanCache.id}"), admin.calls.filter { it.startsWith("startTask") })
        assertEquals(AdminNotice(R.string.admin_task_started, listOf("Clean Cache Directory"), R.string.admin_untitled), vm.state.value.frame.notice)
        assertEquals("kicked", 2, loads())
        admin.tasks = listOf(running)
        vm.refresh()
        vm.run(running.id)
        assertEquals("a running task is not started again", 1, admin.calls.count { it.startsWith("startTask") })
        vm.setVisible(false)
    }

    @Test fun stopNeedsTheConfirmation() = main.adminTest(models) {
        admin.tasks = listOf(running, cleanCache)
        val vm = vm()
        vm.setVisible(true)
        vm.askStop(cleanCache.id)
        assertNull("an idle task cannot be stopped", vm.state.value.confirm)
        vm.askStop(running.id)
        assertEquals(StopTaskConfirm(running.id, "Scan Media Library"), vm.state.value.confirm)
        assertTrue("nothing sent before confirming", admin.calls.none { it.startsWith("stopTask") })
        vm.confirmStop()
        assertEquals(listOf("stopTask:${running.id}"), admin.calls.filter { it.startsWith("stopTask") })
        assertNull(vm.state.value.confirm)
        vm.askStop(running.id)
        vm.dismissConfirm()
        vm.confirmStop()
        assertEquals("dismissed: nothing sent", 1, admin.calls.count { it.startsWith("stopTask") })
        vm.setVisible(false)
    }

    @Test fun aTaskNoLongerRunningIsNotStopped() = main.adminTest(models) {
        admin.tasks = listOf(running)
        val vm = vm()
        vm.setVisible(true)
        vm.askStop(running.id)
        admin.tasks = listOf(fullScanIdle)
        vm.refresh()
        val before = loads()
        vm.confirmStop()
        assertTrue(admin.calls.none { it.startsWith("stopTask") })
        assertEquals(AdminProblem.GONE, vm.state.value.frame.problem)
        assertEquals("refreshed", before + 1, loads())
        vm.setVisible(false)
    }

    @Test fun aFailedStopIsShownOnce() = main.adminTest(models) {
        admin.tasks = listOf(running)
        val vm = vm()
        vm.setVisible(true)
        admin.failures["stopTask"] = AppError.of(AppError.Kind.NETWORK)
        vm.askStop(running.id)
        vm.confirmStop()
        advanceTimeBy(60_000)
        assertEquals(AdminProblem.NETWORK, vm.state.value.frame.problem)
        assertEquals(1, admin.calls.count { it.startsWith("stopTask") })
        vm.setVisible(false)
    }

    @Test fun theConfirmationIsDroppedWhenTheGateCloses() = main.adminTest(models) {
        admin.tasks = listOf(running)
        val vm = vm()
        vm.setVisible(true)
        vm.askStop(running.id)
        catalog.activeFlow.value = AccountSummary(S1, U2, "kid")
        assertEquals(AdminGate.Closed, vm.state.value.frame.gate)
        assertNull(vm.state.value.confirm)
        assertTrue(vm.state.value.groups.isEmpty())
        vm.confirmStop()
        vm.run(cleanCache.id)
        assertTrue(admin.calls.none { it.startsWith("stopTask") || it.startsWith("startTask") })
    }
}
