package app.shoalwatch.ui.admin

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.core.AppError
import app.shoalwatch.data.admin.ActivityEntry
import app.shoalwatch.data.admin.ActivityPage
import app.shoalwatch.data.admin.Severity
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.FakeConnections
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.address
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val ben = AccountSummary(S1, U1, "ben")
    private val catalog = FakeCatalog(accounts = listOf(ben, AccountSummary(S1, U2, "kid")), active = ben)
    private val rights = FakeAdminRights()
    private val connections = FakeConnections().apply { stateFor(S1).value = Connection.Online(address("http://192.168.1.10:8096"), isHome = true) }
    private val admin = FakeAdmin()

    /** A log of [total] entries, id = position (0 newest), one minute apart. */
    private fun serveLog(total: Int, shift: Int = 0) {
        admin.activity = { start, limit ->
            val from = (start - shift).coerceAtLeast(0)
            val ids = (from until minOf(total, from + limit))
            ActivityPage(ids.map(::entry), start + ids.count(), endReached = from + limit >= total)
        }
    }

    private fun entry(id: Int) = ActivityEntry(id.toLong(), "entry $id", null, AdminSamples.now.minusSeconds(60L * id), Severity.INFORMATION)

    private val models = ModelsInTest()
    private fun vm() = models.add(ActivityViewModel(catalog, rights, connections, admin) { AdminSamples.now })
    private fun asked() = admin.calls.filter { it.startsWith("activity:") }

    @Test fun firstPageThenOlderFromTheNextIndex() = main.adminTest(models) {
        serveLog(120)
        val vm = vm()
        vm.setVisible(true)
        assertEquals(listOf("activity:0"), asked())
        assertEquals(ACTIVITY_PAGE, vm.state.value.entries.size)
        assertTrue(vm.state.value.canLoadMore)
        vm.loadMore()
        assertEquals(listOf("activity:0", "activity:50"), asked())
        assertEquals((0 until 100).map { it.toLong() }, vm.state.value.entries.map { it.id })
        vm.loadMore()
        assertEquals(120, vm.state.value.entries.size)
        assertTrue(vm.state.value.endReached)
        assertFalse(vm.state.value.canLoadMore)
        vm.loadMore()
        assertEquals("nothing asked after the end", 3, asked().size)
        advanceTimeBy(10 * 60_000L)
        assertEquals("no polling", 3, asked().size)
        vm.setVisible(false)
    }

    @Test fun overlappingPagesAreDeduplicatedNewestFirst() = main.adminTest(models) {
        serveLog(200)
        val vm = vm()
        vm.setVisible(true)
        serveLog(200, shift = 5) // five new entries arrived: the next page starts five earlier
        vm.loadMore()
        val ids = vm.state.value.entries.map { it.id }
        assertEquals(ids.distinct(), ids)
        assertEquals((0 until 95).map { it.toLong() }, ids)
        vm.setVisible(false)
    }

    @Test fun aSecondLoadMoreWhileOneRunsIsIgnored() = main.adminTest(models) {
        serveLog(200)
        val vm = vm()
        vm.setVisible(true)
        admin.gate = CompletableDeferred()
        vm.loadMore()
        assertTrue(vm.state.value.loadingMore)
        vm.loadMore()
        assertEquals(2, asked().size)
        admin.gate!!.complete(Unit)
        admin.gate = null
        assertFalse(vm.state.value.loadingMore)
        assertEquals(100, vm.state.value.entries.size)
        vm.setVisible(false)
    }

    @Test fun theCapStopsAsking() = main.adminTest(models) {
        serveLog(5_000)
        val vm = vm()
        vm.setVisible(true)
        repeat(30) { vm.loadMore() }
        assertEquals(ACTIVITY_MAX_ENTRIES, vm.state.value.entries.size)
        assertTrue(vm.state.value.limitReached)
        assertFalse(vm.state.value.canLoadMore)
        assertEquals(ACTIVITY_MAX_ENTRIES / ACTIVITY_PAGE, asked().size)
        vm.setVisible(false)
    }

    @Test fun refreshStartsAgainFromTheNewest() = main.adminTest(models) {
        serveLog(200)
        val vm = vm()
        vm.setVisible(true)
        vm.loadMore()
        assertEquals(100, vm.state.value.entries.size)
        vm.refresh()
        assertEquals("activity:0", asked().last())
        assertEquals(ACTIVITY_PAGE, vm.state.value.entries.size)
        vm.setVisible(false)
    }

    @Test fun aFailedLoadMoreKeepsTheEntriesAndIsNotRetried() = main.adminTest(models) {
        serveLog(200)
        val vm = vm()
        vm.setVisible(true)
        admin.failures["activity"] = AppError.of(AppError.Kind.NETWORK)
        vm.loadMore()
        advanceTimeBy(5 * 60_000L)
        assertEquals(AdminProblem.NETWORK, vm.state.value.frame.problem)
        assertEquals(ACTIVITY_PAGE, vm.state.value.entries.size)
        assertEquals(2, asked().size)
        assertTrue("the button stays", vm.state.value.canLoadMore)
        assertFalse(vm.state.value.loadingMore)
        vm.setVisible(false)
    }

    @Test fun closedClearsEverything() = main.adminTest(models) {
        serveLog(200)
        val vm = vm()
        vm.setVisible(true)
        catalog.activeFlow.value = AccountSummary(S1, U2, "kid")
        assertEquals(AdminGate.Closed, vm.state.value.frame.gate)
        assertTrue(vm.state.value.entries.isEmpty())
        vm.loadMore()
        assertEquals(1, asked().size)
        assertNull(vm.state.value.frame.problem)
    }
}
