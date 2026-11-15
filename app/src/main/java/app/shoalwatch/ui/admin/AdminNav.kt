package app.shoalwatch.ui.admin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import app.shoalwatch.AppGraph
import app.shoalwatch.ui.nav.graphViewModel

/**
 * The Server admin routes. None carries an argument: nothing about the server or its users travels in a route, and each screen
 * acts as the session that is active when it opens. They are deliberately not account routes, so `ClearBrowsingOnSessionChange`
 * pops them on any session change (see "Server admin" in docs/ARCHITECTURE.md).
 */
internal object AdminRoutes {
    const val DASHBOARD = "admin"
    const val LIBRARIES = "admin/libraries"
    const val TASKS = "admin/tasks"
    const val USERS = "admin/users"
    const val ACTIVITY = "admin/activity"
    const val DEVICES = "admin/devices"
    const val POWER = "admin/power"

    val ALL = setOf(DASHBOARD, LIBRARIES, TASKS, USERS, ACTIVITY, DEVICES, POWER)
}

/** The real admin destinations, each built from [graph] with `graphViewModel` (no arguments: nothing about the server travels in a route). */
internal fun NavGraphBuilder.adminDestinations(graph: AppGraph, nav: NavHostController) = adminDestinations(
    dashboard = { DashboardRoute(graph, nav) },
    // The other screens come in Tasks 8 to 11; until then their links lead straight back.
    libraries = { NotBuiltYet(nav) },
    tasks = { NotBuiltYet(nav) },
    users = { NotBuiltYet(nav) },
    activity = { NotBuiltYet(nav) },
    devices = { NotBuiltYet(nav) },
    power = { NotBuiltYet(nav) },
)

/** The same routes with the screens passed in, so `AdminNavigationTest` drives this exact graph with recorders. */
internal fun NavGraphBuilder.adminDestinations(
    dashboard: @Composable () -> Unit,
    libraries: @Composable () -> Unit,
    tasks: @Composable () -> Unit,
    users: @Composable () -> Unit,
    activity: @Composable () -> Unit,
    devices: @Composable () -> Unit,
    power: @Composable () -> Unit,
) {
    composable(AdminRoutes.DASHBOARD) { dashboard() }
    composable(AdminRoutes.LIBRARIES) { libraries() }
    composable(AdminRoutes.TASKS) { tasks() }
    composable(AdminRoutes.USERS) { users() }
    composable(AdminRoutes.ACTIVITY) { activity() }
    composable(AdminRoutes.DEVICES) { devices() }
    composable(AdminRoutes.POWER) { power() }
}

/** Pops every admin screen at once; does nothing unless an admin screen is on top (so it can never pop Home). */
internal fun NavHostController.leaveAdmin() {
    fun top() = currentBackStack.value.lastOrNull { it.destination !is NavGraph }?.destination?.route
    while (top() in AdminRoutes.ALL) {
        if (!popBackStack()) return
    }
}

/** Opens another admin screen from the dashboard, once (a second tap does not stack it twice). */
internal fun NavHostController.openAdmin(route: String) = navigate(route) { launchSingleTop = true }

/**
 * What every admin route does around its screen: loads only while started (visible), and leaves every admin screen the moment
 * the gate closes (a switch, a sign-out, lost rights, a 401).
 */
@Composable
internal fun AdminRouteEffects(vm: AdminScreenModel, gate: AdminGate, nav: NavHostController) {
    LifecycleStartEffect(vm) {
        vm.setVisible(true)
        onStopOrDispose { vm.setVisible(false) }
    }
    LaunchedEffect(gate) { if (gate is AdminGate.Closed) nav.leaveAdmin() }
}

@Composable
private fun DashboardRoute(graph: AppGraph, nav: NavHostController) {
    val vm = graphViewModel { DashboardViewModel(graph.serverCatalog, graph.adminRights, graph.addressSelector, graph.admin, graph.browserDashboard) }
    val state by vm.state.collectAsStateWithLifecycle()
    AdminRouteEffects(vm, state.frame.gate, nav)
    val context = LocalContext.current
    val actions = remember(vm, nav, context) {
        DashboardActions(
            onBack = { nav.popBackStack() },
            onRefresh = vm::refresh,
            onOpen = nav::openAdmin,
            onOpenDashboard = { vm.openDashboard(context) },
            onDismissProblem = vm::dismissProblem,
            onDismissNotice = vm::dismissNotice,
        )
    }
    DashboardScreen(state, actions)
}

/** Stand-in for an admin screen a later task builds: it shows nothing and goes straight back. */
@Composable
private fun NotBuiltYet(nav: NavHostController) {
    LaunchedEffect(Unit) { nav.popBackStack() }
}
