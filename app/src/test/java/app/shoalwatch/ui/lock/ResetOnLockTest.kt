package app.shoalwatch.ui.lock

import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.shoalwatch.security.LockState
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.SERVER_ID
import app.shoalwatch.ui.browse.account
import app.shoalwatch.ui.nav.ClearBrowsingOnSessionChange
import app.shoalwatch.ui.nav.ResetOnLock
import app.shoalwatch.ui.nav.Routes
import app.shoalwatch.ui.nav.browsingDestinations
import app.shoalwatch.ui.nav.rememberNavControllerUnlessLocked
import app.shoalwatch.ui.testServer
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The real `NavHost` with recorder screens and the real [ResetOnLock] (and [ClearBrowsingOnSessionChange], as in the app). */
@RunWith(RobolectricTestRunner::class)
class ResetOnLockTest {
    @get:Rule val rule = createComposeRule()

    class Recorder : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }

    private lateinit var nav: NavHostController
    private val recorders = mutableMapOf<String, Recorder>()
    private val lock = MutableStateFlow<LockState>(LockState.Open)
    private var hasServers = true
    private val ben = account(BEN, "ben")
    private val amy = account(AMY, "amy")
    private val catalog = FakeCatalog(listOf(testServer(id = SERVER_ID)), listOf(ben, amy), ben)
    private val locked = LockState.Profile(BEN, "ben")
    private var startsLocked = false // what the gate says as the app (re)starts
    private var itemComposed = 0

    @androidx.compose.runtime.Composable
    private fun content() {
        nav = rememberNavControllerUnlessLocked { startsLocked }
        val state by lock.collectAsState()
        ClearBrowsingOnSessionChange(nav, catalog)
        ResetOnLock(nav, state) { hasServers }
        NavHost(nav, startDestination = if (startsLocked) Routes.LOCKED else Routes.HOME) {
            composable(Routes.LOCKED) {}
            composable(Routes.WELCOME) { Text("welcome") }
            composable(Routes.SEARCH) { Text("search") }
            composable(Routes.DOWNLOADS) { Text("downloads") }
            composable(Routes.HOME, listOf(navArgument(Routes.ARG_FRESH) { type = NavType.BoolType; defaultValue = false })) { Text("home") }
            browsingDestinations(
                library = { Text("library") },
                series = { _, _, _ -> recorders["series"] = viewModel(); Text("series") },
                item = { itemComposed++; recorders["item"] = viewModel(); Text("item") },
                player = { _, _ -> Text("player") },
                downloadedSeries = { Text("downloaded") },
            )
        }
    }

    private fun start() {
        rule.setContent { content() }
        rule.waitForIdle()
    }

    private fun go(route: String) { rule.runOnUiThread { nav.navigate(route) }; rule.waitForIdle() }

    private fun stack(): List<String?> = nav.currentBackStack.value.filter { it.destination !is NavGraph }.map { it.destination.route }

    private fun openTwo() {
        go(Routes.series("x"))
        go(Routes.item("y"))
        assertEquals(listOf(Routes.HOME, Routes.SERIES, Routes.ITEM), stack())
    }

    @Test fun aLockLeavesOnlyTheEmptyLockedRouteAndClearsEveryScreensViewModel() {
        start()
        openTwo()
        lock.value = locked
        rule.waitForIdle()
        assertEquals(listOf(Routes.LOCKED), stack())
        assertTrue("series cleared", recorders.getValue("series").cleared)
        assertTrue("item cleared", recorders.getValue("item").cleared)
    }

    @Test fun openAgainIsAFreshHome() {
        start()
        openTwo()
        lock.value = locked
        rule.waitForIdle()
        lock.value = LockState.Open
        rule.waitForIdle()
        assertEquals(listOf(Routes.HOME), stack())
        assertEquals(false, nav.currentBackStackEntry?.arguments?.getBoolean(Routes.ARG_FRESH))
    }

    @Test fun openAgainWithNoServerIsWelcome() {
        start()
        lock.value = LockState.App
        rule.waitForIdle()
        hasServers = false
        lock.value = LockState.Open
        rule.waitForIdle()
        assertEquals(listOf(Routes.WELCOME), stack())
    }

    @Test fun aSessionChangeWhileLockedKeepsOnlyTheLockedRoute() {
        start()
        lock.value = locked
        rule.waitForIdle()
        catalog.activeFlow.value = amy // would take the app to a fresh Home
        rule.waitForIdle()
        assertEquals(listOf(Routes.LOCKED), stack())
    }

    @Test fun anythingNavigatingWhileLockedIsUndone() {
        start()
        lock.value = locked
        rule.waitForIdle()
        go(Routes.item("z")) // a notification's tab, a stale screen
        assertEquals(listOf(Routes.LOCKED), stack())
    }

    /** Final review M3: after process death the saved stack is not restored under a locked start, so its top is never composed. */
    @Test fun aSavedStackIsNotRestoredWhenTheAppStartsLocked() {
        val restore = StateRestorationTester(rule)
        restore.setContent { content() }
        rule.waitForIdle()
        openTwo()
        val composed = itemComposed
        startsLocked = true // the process died with the item on top; the cold start locks
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals("the item was composed again", composed, itemComposed)
        // A fresh stack from the locked route (here the gate is open, so ResetOnLock then goes on to a fresh Home).
        assertEquals(listOf(Routes.HOME), stack())
    }

    @Test fun aSavedStackIsRestoredWhenTheAppIsOpen() {
        val restore = StateRestorationTester(rule)
        restore.setContent { content() }
        rule.waitForIdle()
        openTwo()
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(listOf(Routes.HOME, Routes.SERIES, Routes.ITEM), stack())
    }

    @Test fun aConfigurationChangeWhileLockedKeepsTheLockedRoute() {
        val restore = StateRestorationTester(rule)
        restore.setContent { content() }
        rule.waitForIdle()
        openTwo()
        lock.value = locked
        rule.waitForIdle()
        restore.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(listOf(Routes.LOCKED), stack())
    }
}
