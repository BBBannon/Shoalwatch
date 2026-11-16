package app.shoalwatch.ui.admin

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
import app.shoalwatch.ui.admin.AdminSamples.benUser
import app.shoalwatch.ui.admin.AdminSamples.films
import app.shoalwatch.ui.admin.AdminSamples.guestUser
import app.shoalwatch.ui.admin.AdminSamples.kidUser
import app.shoalwatch.ui.admin.AdminSamples.tv
import app.shoalwatch.ui.admin.AdminSamples.unreadUser
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsersViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val ben = AccountSummary(S1, U1, "ben")
    private val catalog = FakeCatalog(accounts = listOf(ben, AccountSummary(S1, U2, "kid")), active = ben)
    private val rights = FakeAdminRights()
    private val connections = FakeConnections().apply { stateFor(S1).value = Connection.Online(address("http://192.168.1.10:8096"), isHome = true) }
    private val admin = FakeAdmin().apply {
        users = listOf(benUser, kidUser, guestUser, unreadUser)
        libraries = listOf(films, tv)
    }

    private val models = ModelsInTest()
    private fun vm() = models.add(UsersViewModel(catalog, rights, connections, admin) { AdminSamples.now })
    private fun sent() = admin.calls.filter { it.startsWith("setUserDisabled") }

    @Test fun loadsOnceUsersThenLibrariesAndNeverPolls() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        assertEquals(listOf("users", "libraries"), admin.calls)
        advanceTimeBy(5 * 60_000L)
        assertEquals(1, admin.calls.count { it == "users" })
        assertEquals(listOf(benUser, kidUser, guestUser, unreadUser), vm.state.value.users.map { it.user })
        vm.refresh()
        assertEquals(2, admin.calls.count { it == "users" })
        vm.setVisible(false)
    }

    @Test fun libraryNamesAreResolvedInTheUsersOrder() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        val kidRow = vm.state.value.users.first { it.user.id == kidUser.id }
        assertEquals(listOf("TV", "Films"), kidRow.libraryNames)
        assertEquals(LibraryLine.Names(listOf("TV", "Films"), more = 0), libraryLine(kidRow))
        vm.setVisible(false)
    }

    @Test fun theLibrariesLineRules() {
        val user = kidUser
        assertEquals(LibraryLine.All, libraryLine(UserRow(user.copy(allLibraries = true), listOf("Films"))))
        assertEquals(LibraryLine.None, libraryLine(UserRow(user.copy(libraryIds = emptyList()), emptyList())))
        assertEquals(LibraryLine.Count(2), libraryLine(UserRow(user, null)))
        val five = user.copy(libraryIds = listOf("a", "b", "c", "d", "e"))
        assertEquals("beyond three names", LibraryLine.Names(listOf("A", "B", "C"), more = 2), libraryLine(UserRow(five, listOf("A", "B", "C", "D", "E"))))
        val unknown = user.copy(libraryIds = listOf("a", "gone", "b", "gone2"))
        assertEquals("ids without a name count as more", LibraryLine.Names(listOf("A", "B"), more = 2), libraryLine(UserRow(unknown, listOf("A", "B"))))
    }

    @Test fun aLibrariesFailureLeavesTheNamesUnknown() = main.adminTest(models) {
        admin.failures["libraries"] = AppError.of(AppError.Kind.SERVER_ERROR)
        val vm = vm()
        vm.setVisible(true)
        assertNull(vm.state.value.frame.loadProblem)
        assertTrue(vm.state.value.users.isNotEmpty())
        assertTrue(vm.state.value.users.all { it.libraryNames == null })
        vm.setVisible(false)
    }

    @Test fun youAndUnreadAccountsCannotBeAskedAbout() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        vm.askDisable(benUser.id)
        assertNull("not yourself", vm.state.value.confirm)
        vm.askDisable(unreadUser.id)
        assertNull("not without a readable policy", vm.state.value.confirm)
        vm.askEnable(kidUser.id)
        assertNull("kid is not disabled", vm.state.value.confirm)
        vm.askDisable(guestUser.id)
        assertNull("guest is already disabled", vm.state.value.confirm)
        vm.setVisible(false)
    }

    @Test fun disableAfterConfirmationIsSentOnce() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        vm.askDisable(kidUser.id)
        assertEquals(UserConfirm(kidUser.id, "kid", disable = true, isAdministrator = false), vm.state.value.confirm)
        assertTrue(sent().isEmpty())
        vm.confirm()
        assertEquals(listOf("setUserDisabled:${kidUser.id}:true"), sent())
        assertEquals(AdminNotice(R.string.admin_user_disabled_done, listOf("kid"), R.string.admin_unnamed_user), vm.state.value.frame.notice)
        assertNull(vm.state.value.confirm)
        assertEquals("kicked", 2, admin.calls.count { it == "users" })
        vm.setVisible(false)
    }

    @Test fun enableAfterConfirmation() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        vm.askEnable(guestUser.id)
        assertEquals(UserConfirm(guestUser.id, "guest", disable = false, isAdministrator = false), vm.state.value.confirm)
        vm.confirm()
        assertEquals(listOf("setUserDisabled:${guestUser.id}:false"), sent())
        assertEquals(AdminNotice(R.string.admin_user_enabled_done, listOf("guest"), R.string.admin_unnamed_user), vm.state.value.frame.notice)
        vm.setVisible(false)
    }

    @Test fun aUserAlreadyDisabledIsGone() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        vm.askDisable(kidUser.id)
        admin.users = listOf(benUser, kidUser.copy(isDisabled = true))
        vm.refresh()
        vm.confirm()
        assertTrue(sent().isEmpty())
        assertEquals(AdminProblem.GONE, vm.state.value.frame.problem)
        assertEquals("refreshed", 3, admin.calls.count { it == "users" })
        vm.setVisible(false)
    }

    @Test fun aFailureIsShownOnce() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        admin.failures["setUserDisabled"] = AppError.of(AppError.Kind.NETWORK)
        vm.askDisable(kidUser.id)
        vm.confirm()
        advanceTimeBy(60_000)
        assertEquals(AdminProblem.NETWORK, vm.state.value.frame.problem)
        assertEquals(1, sent().size)
        vm.setVisible(false)
    }

    @Test fun closedClearsEverything() = main.adminTest(models) {
        val vm = vm()
        vm.setVisible(true)
        vm.askDisable(kidUser.id)
        catalog.activeFlow.value = AccountSummary(S1, U2, "kid")
        assertEquals(AdminGate.Closed, vm.state.value.frame.gate)
        assertTrue(vm.state.value.users.isEmpty())
        assertNull(vm.state.value.confirm)
        vm.confirm()
        assertTrue(sent().isEmpty())
    }
}
