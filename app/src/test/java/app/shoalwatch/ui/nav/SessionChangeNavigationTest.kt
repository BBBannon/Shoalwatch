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
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import app.shoalwatch.ui.shell.Tab
import kotlinx.coroutines.flow.MutableStateFlow
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

    /** Every Home entry that was ever composed: one per Home opened. */
    private val homeEntries = linkedSetOf<String>()

    /** Stands in for a screen's ViewModel: the instance each screen was last composed with. */
    class Holder : ViewModel() {
        val signedIn = MutableStateFlow(false)
    }
    private val holders = mutableMapOf<String, Holder>()
    private val serverArg = listOf(navArgument(Routes.ARG_SERVER) { type = NavType.StringType })

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
                    SideEffect { homeCompositions += "home"; homeEntries += it.id }
                    Text("home")
                }
                composable(Routes.SEARCH) { holders["search"] = viewModel(); Text("search") }
                composable(Routes.DOWNLOADS) { Text("downloads") }
                composable(Routes.SERVERS) { holders["servers"] = viewModel(); Text("servers") }
                composable(Routes.ADD_SERVER) { holders["add"] = viewModel(); Text("add server") }
                composable(Routes.SERVER_EDIT, serverArg) { holders["edit"] = viewModel(); Text("server settings") }
                composable(Routes.SIGN_IN, serverArg) {
                    // What SignInRoute does: once its ViewModel says signed in, it goes to a fresh Home itself.
                    val h: Holder = viewModel()
                    holders["sign_in"] = h
                    val signedIn by h.signedIn.collectAsState()
                    LaunchedEffect(signedIn) { if (signedIn) nav.toHome() }
                    Text("sign in")
                }
                browsingDestinations(library = { Text("library") }, series = { _, _, _ -> Text("series") }, item = { Text("item") })
            }
        }
        rule.waitForIdle()
    }

    private fun go(route: String) { rule.runOnUiThread { nav.navigate(route) }; rule.waitForIdle() }

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

    @Test fun switchingOnHomesMenuLandsOnHomeAlone() {
        start()
        catalog.activeFlow.value = amy
        rule.waitForIdle()
        assertOnlyHome()
    }

    // --- account management screens are not tied to a session (I1) -------------------------------------------

    @Test fun switchingOnTheServersScreenLeavesTheUserOnServersWithEverythingOpenedBeforeGone() {
        start()
        open(Destination.Library(view))
        go(Routes.SERVERS)
        val servers = holders.getValue("servers")
        catalog.activeFlow.value = amy
        rule.waitForIdle()
        assertEquals(listOf(Routes.HOME, Routes.SERVERS), stack())
        assertSame("the same Servers screen and ViewModel: nothing it was doing is lost", servers, holders["servers"])
    }

    @Test fun signingOutOfTheActiveAccountOnServersKeepsTheUserThereSoTheNoticeIsSeen() {
        start()
        openThreeAsBen()
        go(Routes.SERVERS)
        val servers = holders.getValue("servers")
        catalog.activeFlow.value = null
        rule.waitForIdle()
        assertEquals("every browsing screen of the old session is gone", listOf(Routes.HOME, Routes.SERVERS), stack())
        assertEquals(Routes.SERVERS, nav.currentBackStackEntry?.destination?.route)
        assertSame("its ViewModel (which shows the signed-out-here-only notice) lives on", servers, holders["servers"])
    }

    @Test fun removingTheLastServerOnServersGoesToWelcomeUnderServers() {
        start()
        openThreeAsBen()
        go(Routes.SERVERS)
        catalog.remove(SERVER_ID)
        rule.waitForIdle()
        assertEquals(listOf(Routes.WELCOME, Routes.SERVERS), stack())
    }

    @Test fun aServersSettingsScreenStaysOnTopOfHome() {
        start()
        open(Destination.Library(view))
        go(Routes.SERVERS)
        go(Routes.serverEdit(SERVER_ID))
        val edit = holders.getValue("edit")
        catalog.activeFlow.value = amy
        rule.waitForIdle()
        assertEquals(listOf(Routes.HOME, Routes.SERVER_EDIT), stack())
        assertEquals(SERVER_ID, nav.currentBackStackEntry?.arguments?.getString(Routes.ARG_SERVER))
        assertSame(edit, holders["edit"])
    }

    // --- sign-in and the reset racing (M1) ----------------------------------------------------------------------

    @Test fun whenTheStoreAnnouncesTheSignInFirstTheSignInStillEndsOnOneFreshHome() {
        start()
        open(Destination.Library(view))
        go(Routes.SERVERS)
        go(Routes.signIn(SERVER_ID))
        val signIn = holders.getValue("sign_in")
        val composedBefore = homeEntries.size
        catalog.activeFlow.value = amy // the store's answer arrives before the sign-in screen has navigated
        rule.waitForIdle()
        assertEquals(listOf(Routes.HOME, Routes.SIGN_IN), stack())
        assertSame("the sign-in in progress is not lost", signIn, holders["sign_in"])
        signIn.signedIn.value = true
        rule.waitForIdle()
        assertEquals(listOf(Routes.HOME), stack())
        assertEquals("fresh is kept", true, nav.currentBackStackEntry?.arguments?.getBoolean(Routes.ARG_FRESH))
        assertEquals("Home is opened once", composedBefore + 1, homeEntries.size)
    }

    @Test fun whenBothArriveInTheSameFrameTheSignInEndsOnOneFreshHome() {
        start()
        go(Routes.SERVERS)
        go(Routes.signIn(SERVER_ID))
        val composedBefore = homeEntries.size
        rule.runOnUiThread {
            holders.getValue("sign_in").signedIn.value = true
            catalog.activeFlow.value = amy
        }
        rule.waitForIdle()
        assertEquals(listOf(Routes.HOME), stack())
        assertEquals(true, nav.currentBackStackEntry?.arguments?.getBoolean(Routes.ARG_FRESH))
        assertEquals("Home is opened once", composedBefore + 1, homeEntries.size)
    }

    // --- the tabs' saved states go on every path (M3) ----------------------------------------------------------

    @Test fun aScreenSavedUnderTheHomeTabIsNotBroughtBackAfterTheSwitch() {
        start()
        open(Destination.Library(view))
        rule.runOnUiThread { nav.switchTab(Tab.SEARCH) } // the library is saved with the Home tab
        rule.waitForIdle()
        assertEquals(listOf(Routes.HOME, Routes.SEARCH), stack())
        catalog.activeFlow.value = amy
        rule.waitForIdle()
        assertOnlyHome()
        rule.runOnUiThread { nav.switchTab(Tab.SEARCH) }
        rule.waitForIdle()
        rule.runOnUiThread { nav.switchTab(Tab.HOME) }
        rule.waitForIdle()
        assertOnlyHome()
    }

    @Test fun theSavedSearchTabIsClearedEvenWhenAFreshHomeIsLeftAlone() {
        start()
        rule.runOnUiThread { nav.navigate(Routes.home(fresh = true)) { popUpTo(nav.graph.id) { inclusive = true } } }
        rule.waitForIdle()
        rule.runOnUiThread { nav.switchTab(Tab.SEARCH) }
        rule.waitForIdle()
        val search = holders.getValue("search")
        rule.runOnUiThread { nav.switchTab(Tab.HOME) } // Search is saved
        rule.waitForIdle()
        catalog.activeFlow.value = amy // a fresh Home alone: it stays, but the saved Search does not
        rule.waitForIdle()
        assertEquals(true, nav.currentBackStackEntry?.arguments?.getBoolean(Routes.ARG_FRESH))
        rule.runOnUiThread { nav.switchTab(Tab.SEARCH) }
        rule.waitForIdle()
        assertTrue("a new Search, not the one saved for the old account", search !== holders["search"])
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

    // --- a store read that fails is not a sign-out (I4) ----------------------------------------------------------

    @Test fun aStoreReadFailureIsNotASignOutTheUserStaysWhereTheyAre() {
        start()
        openThreeAsBen()
        val top = nav.currentBackStackEntry
        catalog.failReads()
        rule.waitForIdle()
        assertEquals(listOf(Routes.HOME, Routes.LIBRARY, Routes.SERIES, Routes.ITEM), stack())
        catalog.healReads()
        runBlocking { catalog.retryWarmIfFailed() }
        rule.waitForIdle()
        assertSame("the same account came back: nothing moved", top, nav.currentBackStackEntry)
        assertEquals(listOf(Routes.HOME, Routes.LIBRARY, Routes.SERIES, Routes.ITEM), stack())
    }

    @Test fun aSignOutDuringAStoreFailureClearsTheStackOnceTheStoreCanBeReadAgain() {
        start()
        openThreeAsBen()
        catalog.failReads()
        rule.waitForIdle()
        catalog.activeFlow.value = null
        catalog.healReads()
        runBlocking { catalog.retryWarmIfFailed() }
        rule.waitForIdle()
        assertOnlyHome()
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
