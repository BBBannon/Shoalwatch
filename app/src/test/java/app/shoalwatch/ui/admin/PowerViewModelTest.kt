package app.shoalwatch.ui.admin

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import app.shoalwatch.R
import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.address
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PowerViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val ben = AccountSummary(S1, U1, "ben")
    private val catalog = FakeCatalog(accounts = listOf(ben, AccountSummary(S1, U2, "kid")), active = ben)
    private val rights = FakeAdminRights()
    private val connections = FakeConnections().apply { stateFor(S1).value = Connection.Online(address("http://192.168.1.10:8096"), isHome = true) }
    private val admin = FakeAdmin()
    private val models = ModelsInTest()

    private fun vm() = models.add(
        PowerViewModel(catalog, rights, connections, admin, { AdminSamples.now }, words = { if (it == PowerAction.RESTART) "restart" else "shut down" }),
    )
    private fun sent() = admin.calls.filter { it == "restart" || it == "shutDown" }

    private fun PowerViewModel.type(text: String) {
        typed.setTextAndPlaceCursorAtEnd(text)
        Snapshot.sendApplyNotifications()
    }

    @Test fun loadsTheServerOnce() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        assertEquals(admin.status, vm.state.value.status)
        assertTrue(vm.state.value.canAsk)
        advanceTimeBy(5 * 60_000L)
        assertEquals(1, admin.calls.count { it == "serverStatus" })
    }

    @Test fun nothingIsSentWithoutTheTypedWord() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        vm.ask(PowerAction.RESTART)
        assertEquals(PowerAction.RESTART, vm.state.value.confirm)
        vm.confirm()
        vm.type("restar")
        runCurrent()
        assertFalse(vm.state.value.canConfirm)
        vm.confirm()
        assertTrue(sent().isEmpty())
        assertEquals("the dialog stays open", PowerAction.RESTART, vm.state.value.confirm)
    }

    @Test fun theRestartWordDoesNotShutDown() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        vm.ask(PowerAction.SHUT_DOWN)
        vm.type("Restart")
        runCurrent()
        assertFalse(vm.state.value.canConfirm)
        vm.confirm()
        assertTrue(sent().isEmpty())
        vm.type("  Shut   DOWN ")
        runCurrent()
        assertTrue(vm.state.value.canConfirm)
    }

    @Test fun theTypedWordSendsOnceAndSwitchesTheButtonsOff() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        vm.ask(PowerAction.RESTART)
        vm.type(" RESTART ")
        runCurrent()
        assertTrue(vm.state.value.canConfirm)
        vm.confirm()
        assertEquals(listOf("restart"), sent())
        assertEquals(PowerAction.RESTART, vm.state.value.requested)
        assertFalse(vm.state.value.canAsk)
        assertNull(vm.state.value.confirm)
        assertEquals("", vm.typed.text.toString())
        assertEquals(AdminNotice(R.string.admin_restart_requested), vm.state.value.frame.notice)
        vm.ask(PowerAction.SHUT_DOWN)
        assertNull("nothing more is asked after a request", vm.state.value.confirm)
        advanceTimeBy(10 * 60_000L)
        assertEquals("never re-sent", listOf("restart"), sent())
    }

    @Test fun aFailureIsShownOnceAndClearsTheWord() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        admin.failures["shutDown"] = AppError.of(AppError.Kind.NETWORK)
        vm.ask(PowerAction.SHUT_DOWN)
        vm.type("shut down")
        vm.confirm()
        advanceTimeBy(5 * 60_000L)
        assertEquals(listOf("shutDown"), sent())
        assertEquals(AdminProblem.NETWORK, vm.state.value.frame.problem)
        assertNull(vm.state.value.requested)
        assertEquals("", vm.typed.text.toString())
        assertTrue(vm.state.value.canAsk)
    }

    @Test fun offlineSendsNothing() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        vm.ask(PowerAction.RESTART)
        vm.type("restart")
        connections.stateFor(S1).value = Connection.Offline()
        vm.confirm()
        assertTrue(sent().isEmpty())
        assertFalse(vm.state.value.canAsk)
    }

    @Test fun theGateClosingDropsTheDialogAndTheWord() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        vm.ask(PowerAction.SHUT_DOWN)
        vm.type("shut down")
        catalog.activeFlow.value = AccountSummary(S1, U2, "kid")
        assertEquals(AdminGate.Closed, vm.state.value.frame.gate)
        assertNull(vm.state.value.confirm)
        assertNull(vm.state.value.status)
        assertEquals("", vm.typed.text.toString())
        vm.type("shut down")
        vm.confirm()
        assertTrue(sent().isEmpty())
    }
}
