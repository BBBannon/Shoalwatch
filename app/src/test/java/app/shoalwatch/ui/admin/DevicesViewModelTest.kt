package app.shoalwatch.ui.admin

import app.shoalwatch.R
import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.address
import app.shoalwatch.ui.admin.AdminSamples.commaDevice
import app.shoalwatch.ui.admin.AdminSamples.phoneDevice
import app.shoalwatch.ui.admin.AdminSamples.tvDevice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val ben = AccountSummary(S1, U1, "ben")
    private val catalog = FakeCatalog(accounts = listOf(ben, AccountSummary(S1, U2, "kid")), active = ben)
    private val rights = FakeAdminRights()
    private val online = Connection.Online(address("http://192.168.1.10:8096"), isHome = true)
    private val connections = FakeConnections().apply { stateFor(S1).value = online }
    private val admin = FakeAdmin().apply { devices = listOf(tvDevice, phoneDevice) }

    private val models = ModelsInTest()
    private fun vm() = models.add(DevicesViewModel(catalog, rights, connections, admin) { AdminSamples.now })
    private fun removed() = admin.calls.filter { it.startsWith("removeDevice") }

    @Test fun loadsOnceAndOnRefresh() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        assertEquals(listOf(tvDevice, phoneDevice), vm.state.value.devices)
        advanceTimeBy(5 * 60_000L)
        assertEquals(1, admin.calls.count { it == "devices" })
        vm.refresh()
        assertEquals(2, admin.calls.count { it == "devices" })
        vm.setVisible(false)
    }

    @Test fun thisPhoneCannotBeAskedAbout() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        vm.askRemove(phoneDevice.id)
        assertNull(vm.state.value.confirm)
        vm.askRemove("unknown")
        assertNull(vm.state.value.confirm)
        vm.setVisible(false)
    }

    @Test fun aDeviceThatIsNotRemovableCannotBeAskedAbout() = main.adminTest(models) {
        admin.devices = listOf(tvDevice, commaDevice)
        val vm = vm()
        vm.setVisible(true)
        vm.askRemove(commaDevice.id)
        assertNull(vm.state.value.confirm)
        vm.confirmRemove()
        assertTrue(removed().isEmpty())
    }

    @Test fun removeAfterConfirmationIsSentOnce() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        vm.askRemove(tvDevice.id)
        assertEquals(DeviceConfirm(tvDevice.id, "Living room TV", "kid, ben"), vm.state.value.confirm)
        assertTrue(removed().isEmpty())
        vm.confirmRemove()
        assertEquals(listOf("removeDevice:${tvDevice.id}"), removed())
        assertEquals(Session(S1, U1), admin.sessionsSeen[admin.calls.indexOf("removeDevice:${tvDevice.id}")])
        assertEquals(AdminNotice(R.string.admin_device_removed, listOf("Living room TV"), R.string.admin_unnamed_device), vm.state.value.frame.notice)
        assertNull(vm.state.value.confirm)
        assertEquals("kicked", 2, admin.calls.count { it == "devices" })
        vm.setVisible(false)
    }

    @Test fun aDeviceGoneFromTheLatestListIsNotRemoved() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        vm.askRemove(tvDevice.id)
        admin.devices = listOf(phoneDevice)
        vm.refresh()
        vm.confirmRemove()
        assertTrue(removed().isEmpty())
        assertEquals(AdminProblem.GONE, vm.state.value.frame.problem)
        assertEquals(3, admin.calls.count { it == "devices" })
        vm.setVisible(false)
    }

    @Test fun aFailureIsShownOnce() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        admin.failures["removeDevice"] = AppError.of(AppError.Kind.NETWORK)
        vm.askRemove(tvDevice.id)
        vm.confirmRemove()
        advanceTimeBy(5 * 60_000L)
        assertEquals(AdminProblem.NETWORK, vm.state.value.frame.problem)
        assertEquals(1, removed().size)
        vm.setVisible(false)
    }

    @Test fun offlineSendsNothing() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        vm.askRemove(tvDevice.id)
        connections.stateFor(S1).value = Connection.Offline()
        vm.confirmRemove()
        assertTrue(removed().isEmpty())
        assertEquals(AdminProblem.OFFLINE, vm.state.value.frame.problem)
        vm.setVisible(false)
    }

    @Test fun closedWithTheDialogOpenDropsItAndSendsNothing() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        vm.askRemove(tvDevice.id)
        catalog.activeFlow.value = AccountSummary(S1, U2, "kid")
        assertEquals(AdminGate.Closed, vm.state.value.frame.gate)
        assertNull(vm.state.value.confirm)
        assertTrue(vm.state.value.devices.isEmpty())
        vm.confirmRemove()
        assertTrue(removed().isEmpty())
    }
}
