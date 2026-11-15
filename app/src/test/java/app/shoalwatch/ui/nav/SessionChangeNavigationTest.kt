package app.shoalwatch.ui.nav

import androidx.compose.material3.Text
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.SERVER_ID
import app.shoalwatch.ui.browse.account
import app.shoalwatch.ui.present.Destination
import app.shoalwatch.ui.testServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The real navigation graph pieces (`Routes`, `open`, `browsingDestinations`) with the real [ClearBrowsingOnSessionChange]; only the
 * screens are stand-ins. Whatever one account opened is gone when the session changes, wherever the change came from.
 */
@RunWith(RobolectricTestRunner::class)
class SessionChangeNavigationTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var nav: NavHostController
    private val homeCompositions = mutableListOf<String>()

    private val ben = account(BEN, "ben")
    private val amy = account(AMY, "amy")
    private val catalog = FakeCatalog(listOf(testServer(id = SERVER_ID)), listOf(ben, amy), ben)

    private val view = "a".repeat(32)
    private val series = "b".repeat(32)
    private val item = "c".repeat(32)

    private fun start(startRoute: String = Routes.HOME) {
        rule.setContent {
            nav = rememberNavController()
            ClearBrowsingOnSessionChange(nav, catalog)
            NavHost(nav, startDestination = startRoute) {
                composable(Routes.WELCOME) { Text("welcome") }
                composable(Routes.HOME, listOf(navArgument(Routes.ARG_FRESH) { type = NavType.BoolType; defaultValue = false })) {
                    SideEffect { homeCompositions += "home" }
                    Text("home")
                }
                composable(Routes.SEARCH) { Text("search") }
                composable(Routes.DOWNLOADS) { Text("downloads") }
                composable(Routes.SERVERS) { Text("servers") }
                browsingDestinations(library = { Text("library") }, series = { _, _, _ -> Text("series") }, item = { Text("item") })
            }
        }
        rule.waitForIdle()
    }

    private fun open(d: Destination) { rule.runOnUiThread { nav.open(d) }; rule.waitForIdle() }

    private fun stack(): List<String?> = nav.currentBackStack.value.filter { it.destination !is NavGraph }.map { it.destination.route }

    private fun openThreeAsBen() {
        open(Destination.Library(view))
        open(Destination.Series(series))
        open(Destination.Detail(item))
        assertEquals(listOf(Routes.HOME, Routes.LIBRARY, Routes.SERIES, Routes.ITEM), stack())
    }

    private fun assertOnlyHome() {
        assertEquals(listOf(Routes.HOME), stack())
        assertEquals(Routes.HOME, nav.currentBackStackEntry?.destination?.route)
    }

    @Test fun switchingAccountClearsEverythingTheOldAccountOpenedAndLandsOnHome() {
        start()
        openThreeAsBen()
        catalog.activeFlow.value = amy
        rule.waitForIdle()
        assertOnlyHome()
    }

    @Test fun switchingFromTheServersScreenDoesTheSame() {
        start()
        open(Destination.Library(view))
        rule.runOnUiThread { nav.navigate(Routes.SERVERS) }
        rule.waitForIdle()
        catalog.activeFlow.value = amy
        rule.waitForIdle()
        assertOnlyHome()
    }

    @Test fun signingOutClearsTheStackToHomeWhileAServerIsLeft() {
        start()
        openThreeAsBen()
        catalog.activeFlow.value = null
        rule.waitForIdle()
        assertOnlyHome()
    }

    @Test fun removingTheLastServerClearsTheStackToWelcome() {
        start()
        openThreeAsBen()
        catalog.remove(SERVER_ID)
        rule.waitForIdle()
        assertEquals(listOf(Routes.WELCOME), stack())
    }

    @Test fun nothingPoppedIsBroughtBackByBack() {
        start()
        openThreeAsBen()
        catalog.activeFlow.value = amy
        rule.waitForIdle()
        rule.runOnUiThread { nav.popBackStack() }
        rule.waitForIdle()
        assertEquals("only the start destination is left, so Back does not restore anything", emptyList<String?>(), stack())
    }

    @Test fun theFirstSessionAtStartDoesNotNavigateAgain() {
        start()
        val first = nav.currentBackStackEntry
        assertEquals(1, homeCompositions.size)
        rule.waitForIdle()
        assertSame(first, nav.currentBackStackEntry)
        assertEquals(1, homeCompositions.size)
    }

    @Test fun signingInFromNobodyDoesNotNavigateTheSignInFlowHasItsOwnNavigation() {
        catalog.activeFlow.value = null
        start()
        open(Destination.Library(view))
        val before = nav.currentBackStackEntry
        catalog.activeFlow.value = ben
        rule.waitForIdle()
        assertSame(before, nav.currentBackStackEntry)
        assertEquals(listOf(Routes.HOME, Routes.LIBRARY), stack())
    }

    @Test fun aFreshHomeThatASignInHasAlreadyOpenedIsNotNavigatedAwayFromAndKeepsFresh() {
        start()
        rule.runOnUiThread { nav.navigate(Routes.home(fresh = true)) { popUpTo(nav.graph.id) { inclusive = true } } }
        rule.waitForIdle()
        val before = nav.currentBackStackEntry
        catalog.activeFlow.value = amy
        rule.waitForIdle()
        assertSame(before, nav.currentBackStackEntry)
        assertEquals(true, nav.currentBackStackEntry?.arguments?.getBoolean(Routes.ARG_FRESH))
    }

    @Test fun theSameSessionAnnouncedAgainDoesNothing() {
        start()
        open(Destination.Library(view))
        val before = nav.currentBackStackEntry
        catalog.activeFlow.value = ben.copy(userName = "Ben renamed")
        rule.waitForIdle()
        assertSame(before, nav.currentBackStackEntry)
    }
}
