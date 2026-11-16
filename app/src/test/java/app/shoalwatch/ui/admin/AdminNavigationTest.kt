package app.shoalwatch.ui.admin

import androidx.compose.material3.Text
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
import app.shoalwatch.ui.nav.ClearBrowsingOnSessionChange
import app.shoalwatch.ui.nav.Routes
import app.shoalwatch.ui.testServer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The real admin routes (`adminDestinations`, `leaveAdmin`) and the real `ClearBrowsingOnSessionChange`; only the screens are recorders. */
@RunWith(RobolectricTestRunner::class)
class AdminNavigationTest {
    @get:Rule val rule = createComposeRule()

    private lateinit var nav: NavHostController
    private val shown = mutableListOf<String>()
    private val ben = account(BEN, "ben")
    private val amy = account(AMY, "amy")
    private val catalog = FakeCatalog(listOf(testServer(id = SERVER_ID)), listOf(ben, amy), ben)

    private fun start() {
        rule.setContent {
            nav = rememberNavController()
            ClearBrowsingOnSessionChange(nav, catalog)
            NavHost(nav, startDestination = Routes.HOME) {
                composable(Routes.HOME, listOf(navArgument(Routes.ARG_FRESH) { type = NavType.BoolType; defaultValue = false })) { Text("home") }
                composable(Routes.SERVERS) { Text("servers") }
                composable(Routes.SEARCH) { Text("search") }
                composable(Routes.DOWNLOADS) { Text("downloads") }
                adminDestinations(
                    dashboard = { record("dashboard") }, libraries = { record("libraries") }, tasks = { record("tasks") },
                    users = { record("users") }, activity = { record("activity") }, devices = { record("devices") }, power = { record("power") },
                )
            }
        }
        rule.waitForIdle()
    }

    @androidx.compose.runtime.Composable
    private fun record(name: String) {
        androidx.compose.runtime.SideEffect { shown += name }
        Text(name)
    }

    private fun go(route: String) {
        rule.runOnUiThread { nav.openAdmin(route) }
        rule.waitForIdle()
    }

    private fun stack(): List<String?> = nav.currentBackStack.value.filter { it.destination !is NavGraph }.map { it.destination.route }

    @Test fun everyRouteOpensItsScreen() {
        start()
        val routes = mapOf(
            AdminRoutes.DASHBOARD to "dashboard", AdminRoutes.LIBRARIES to "libraries", AdminRoutes.TASKS to "tasks", AdminRoutes.USERS to "users",
            AdminRoutes.ACTIVITY to "activity", AdminRoutes.DEVICES to "devices", AdminRoutes.POWER to "power",
        )
        for ((route, name) in routes) {
            go(route)
            assertEquals(route, nav.currentBackStackEntry?.destination?.route)
            assertEquals(name, shown.last())
        }
        assertEquals(AdminRoutes.ALL, routes.keys)
    }

    @Test fun openingTheSameScreenTwiceDoesNotStackIt() {
        start()
        go(AdminRoutes.DASHBOARD)
        go(AdminRoutes.DASHBOARD)
        assertEquals(listOf(Routes.HOME, AdminRoutes.DASHBOARD), stack())
    }

    @Test fun leaveAdminPopsEveryAdminScreenAtOnce() {
        start()
        rule.runOnUiThread { nav.navigate(Routes.SERVERS) }
        go(AdminRoutes.DASHBOARD)
        go(AdminRoutes.LIBRARIES)
        assertEquals(listOf(Routes.HOME, Routes.SERVERS, AdminRoutes.DASHBOARD, AdminRoutes.LIBRARIES), stack())
        rule.runOnUiThread { nav.leaveAdmin() }
        rule.waitForIdle()
        assertEquals(listOf(Routes.HOME, Routes.SERVERS), stack())
    }

    @Test fun leaveAdminFromHomeDoesNothing() {
        start()
        rule.runOnUiThread { nav.leaveAdmin() }
        rule.waitForIdle()
        assertEquals(listOf(Routes.HOME), stack())
    }

    @Test fun anAccountSwitchPopsTheAdminScreens() {
        start()
        go(AdminRoutes.DASHBOARD)
        go(AdminRoutes.LIBRARIES)
        catalog.activeFlow.value = amy
        rule.waitForIdle()
        assertEquals("admin routes are session-bound", listOf(Routes.HOME), stack())
    }
}
