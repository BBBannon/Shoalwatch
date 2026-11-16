package app.shoalwatch.ui.admin

import android.content.Context
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.R
import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.core.AppError
import app.shoalwatch.data.admin.AdminStatus
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.address
import app.shoalwatch.ui.admin.AdminSamples.benHere
import app.shoalwatch.ui.admin.AdminSamples.guestPaused
import app.shoalwatch.ui.admin.AdminSamples.kidIdle
import app.shoalwatch.ui.admin.AdminSamples.kidOnTv
import app.shoalwatch.ui.admin.AdminSamples.noControl
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class DashboardSessionsTest {
    @get:Rule val main = MainDispatcherRule()

    private val ben = AccountSummary(S1, U1, "ben")
    private val kid = AccountSummary(S1, U2, "kid")
    private val owner = Session(S1, U1)
    private val catalog = FakeCatalog(accounts = listOf(ben, kid), active = ben)
    private val rights = FakeAdminRights()
    private val online = Connection.Online(address("http://192.168.1.10:8096"), isHome = true)
    private val connections = FakeConnections().apply { stateFor(S1).value = online }
    private val admin = FakeAdmin().apply { sessions = listOf(kidIdle, kidOnTv, benHere, guestPaused, noControl) }
    private val context: Context = ApplicationProvider.getApplicationContext()

    private val models = ModelsInTest()
    private fun vm() = models.add(DashboardViewModel(catalog, rights, connections, admin, FakeDashboardOpener()) { AdminSamples.now })
    private fun loads() = admin.calls.count { it == "sessions" }
    private fun commands() = admin.calls.filter { it.startsWith("command:") }

    @Test fun pollsEveryFiveSecondsOnlyWhileVisibleAndOnline() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        assertEquals("status first, then the sessions", listOf("serverStatus", "sessions"), admin.calls)
        advanceTimeBy(10_500)
        assertEquals("loads at 0, 5 and 10 s", 3, loads())
        vm.setVisible(false)
        advanceTimeBy(60_000)
        assertEquals("nothing while hidden", 3, loads())
        vm.setVisible(true)
        assertEquals("visible again: at once", 4, loads())
        connections.stateFor(S1).value = Connection.Offline()
        advanceTimeBy(60_000)
        assertEquals("nothing while offline", 4, loads())
        connections.stateFor(S1).value = online
        assertEquals("online again: at once", 5, loads())
        assertTrue(admin.sessionsSeen.all { it == owner })
        vm.setVisible(false)
    }

    @Test fun playingBeforeIdleInTheServersOrder() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        assertEquals(listOf(kidOnTv, benHere, guestPaused, noControl), vm.state.value.playing)
        assertEquals(listOf(kidIdle), vm.state.value.idle)
        vm.setVisible(false)
    }

    @Test fun onlyAControllableOtherSessionCanBeAskedAbout() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        vm.askStop(benHere.id)
        assertNull("not this phone", vm.state.value.confirm)
        vm.askStop(noControl.id)
        assertNull("not without media control", vm.state.value.confirm)
        vm.askPause(guestPaused.id)
        assertNull("not a paused one", vm.state.value.confirm)
        vm.askStop("unknown")
        assertNull(vm.state.value.confirm)
        vm.askMessage(benHere.id)
        assertNull(vm.state.value.message)
        vm.askMessage(noControl.id)
        assertNull(vm.state.value.message)
        vm.setVisible(false)
    }

    @Test fun stopAfterConfirmationIsSentOnceAndReloads() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        vm.askStop(kidOnTv.id)
        assertEquals(SessionConfirm.Stop(kidOnTv.id, "kid", "Living room TV", "Title"), vm.state.value.confirm)
        assertEquals("nothing sent before confirming", emptyList<String>(), commands())
        val before = loads()
        vm.confirmCommand()
        assertEquals(listOf("command:${kidOnTv.id}:Stop"), commands())
        assertEquals(owner, admin.sessionsSeen[admin.calls.indexOf("command:${kidOnTv.id}:Stop")])
        assertEquals(AdminNotice(R.string.admin_stopped_done, listOf("kid"), R.string.admin_unnamed_user), vm.state.value.frame.notice)
        assertNull(vm.state.value.confirm)
        assertEquals("an immediate reload", before + 1, loads())
        vm.setVisible(false)
    }

    @Test fun pauseAfterConfirmation() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        vm.askPause(kidOnTv.id)
        assertTrue(vm.state.value.confirm is SessionConfirm.Pause)
        vm.confirmCommand()
        assertEquals(listOf("command:${kidOnTv.id}:Pause"), commands())
        assertEquals(AdminNotice(R.string.admin_paused_done, listOf("kid"), R.string.admin_unnamed_user), vm.state.value.frame.notice)
        vm.setVisible(false)
    }

    @Test fun aFailedCommandIsShownOnceAndNeverRetried() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        admin.failures["command"] = AppError.of(AppError.Kind.NETWORK)
        vm.askStop(kidOnTv.id)
        vm.confirmCommand()
        advanceTimeBy(5 * 60_000L)
        assertEquals(AdminProblem.NETWORK, vm.state.value.frame.problem)
        assertEquals("one call, no retry", 1, commands().size)
        assertNull("the dialog is closed", vm.state.value.confirm)
        assertNull(vm.state.value.frame.notice)
        vm.setVisible(false)
    }

    @Test fun confirmingWhileOfflineSendsNothing() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        vm.askStop(kidOnTv.id)
        connections.stateFor(S1).value = Connection.Offline()
        vm.confirmCommand()
        assertEquals(emptyList<String>(), commands())
        assertEquals(AdminProblem.OFFLINE, vm.state.value.frame.problem)
        vm.setVisible(false)
    }

    @Test fun aSessionThatWentAwayIsNotActedOn() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        vm.askStop(kidOnTv.id)
        admin.sessions = listOf(guestPaused)
        vm.refresh()
        val before = loads()
        vm.confirmCommand()
        assertEquals(emptyList<String>(), commands())
        assertEquals(AdminProblem.GONE, vm.state.value.frame.problem)
        assertEquals("the list is refreshed", before + 1, loads())
        assertNull(vm.state.value.confirm)
        vm.setVisible(false)
    }

    /**
     * The same session id, but kid has moved on to the next episode since the dialog named what is playing: nothing is sent, the
     * admin is told it has changed, and the list is refreshed. The same for a message.
     */
    @Test fun aSessionNowPlayingSomethingElseIsNotActedOn() = main.adminTest(models) {
        val shown = kidOnTv.copy(nowPlaying = AdminSamples.episode.copy(itemId = "a".repeat(32)))
        val moved = kidOnTv.copy(nowPlaying = AdminSamples.episode.copy(itemId = "b".repeat(32), title = "The next one"))
        val vm = vm()
        vm.setVisible(true)
        val asks: List<(String) -> Unit> = listOf(vm::askStop, vm::askPause)
        for (ask in asks) {
            admin.sessions = listOf(shown)
            vm.refresh()
            ask(shown.id)
            admin.sessions = listOf(moved)
            vm.refresh()
            val before = loads()
            vm.confirmCommand()
            assertEquals("nothing is sent for what the dialog did not name", emptyList<String>(), commands())
            assertEquals(AdminProblem.CHANGED, vm.state.value.frame.problem)
            assertEquals("the list is refreshed", before + 1, loads())
            vm.dismissProblem()
        }
        admin.sessions = listOf(shown)
        vm.refresh()
        vm.askMessage(shown.id)
        vm.messageText.setTextAndPlaceCursorAtEnd("Bedtime")
        admin.sessions = listOf(moved)
        vm.refresh()
        vm.sendMessage(context)
        assertTrue("no message is sent", admin.messages.isEmpty())
        assertEquals(AdminProblem.CHANGED, vm.state.value.frame.problem)
        vm.setVisible(false)
    }

    @Test fun aMessageIsTrimmedAndCarriesTheAdminsName() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        vm.askMessage(kidIdle.id)
        assertEquals(MessageTarget(kidIdle.id, "kid", "Kid's tablet"), vm.state.value.message)
        vm.messageText.setTextAndPlaceCursorAtEnd("   ")
        vm.sendMessage(context)
        assertEquals("blank text is refused", emptyList<Triple<String, String, String>>(), admin.messages)
        assertEquals(MessageTarget(kidIdle.id, "kid", "Kid's tablet"), vm.state.value.message)
        vm.messageText.setTextAndPlaceCursorAtEnd("  Bedtime in ten minutes.  ")
        vm.sendMessage(context)
        assertEquals(listOf(Triple(kidIdle.id, "Message from ben", "Bedtime in ten minutes.")), admin.messages)
        assertEquals(listOf(owner), admin.sessionsSeen.filterIndexed { i, _ -> admin.calls[i].startsWith("sendMessage") })
        assertEquals(AdminNotice(R.string.admin_message_sent, listOf("kid"), R.string.admin_unnamed_user), vm.state.value.frame.notice)
        assertEquals("", vm.messageText.text.toString())
        assertNull(vm.state.value.message)
        vm.setVisible(false)
    }

    @Test fun dismissingTheMessageClearsTheText() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        vm.askMessage(kidOnTv.id)
        vm.messageText.setTextAndPlaceCursorAtEnd("Dinner")
        vm.dismissMessage()
        assertNull(vm.state.value.message)
        assertEquals("", vm.messageText.text.toString())
        assertTrue(admin.messages.isEmpty())
        vm.setVisible(false)
    }

    @Test fun switchingAccountWithTheStopDialogOpenDropsEverything() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        vm.askStop(kidOnTv.id)
        vm.messageText.setTextAndPlaceCursorAtEnd("half typed")
        catalog.activeFlow.value = kid
        val state = vm.state.value
        assertEquals(AdminGate.Closed, state.frame.gate)
        assertNull(state.confirm)
        assertNull(state.message)
        assertNull(state.status)
        assertEquals("", vm.messageText.text.toString())
        assertTrue(state.playing.isEmpty() && state.idle.isEmpty())
        vm.confirmCommand()
        vm.askStop(kidOnTv.id)
        vm.confirmCommand()
        vm.askMessage(kidOnTv.id)
        vm.messageText.setTextAndPlaceCursorAtEnd("hello")
        vm.sendMessage(context)
        assertEquals("nothing is sent after the switch", emptyList<String>(), commands())
        assertTrue(admin.messages.isEmpty())
    }

    @Test fun aRefusalThatEndsTheRightsClosesSilently() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        admin.failures["command"] = AppError.of(AppError.Kind.FORBIDDEN)
        rights.answer = AdminStatus.NOT_ADMIN
        vm.askStop(kidOnTv.id)
        vm.confirmCommand()
        assertEquals(AdminGate.Closed, vm.state.value.frame.gate)
        assertNull(vm.state.value.frame.problem)
        assertNull(vm.state.value.frame.notice)
        assertEquals(1, commands().size)
        assertEquals(listOf(owner), rights.lostCalls)
    }
}
