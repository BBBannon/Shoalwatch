package app.shoalwatch.ui.nav

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.shoalwatch.AppGraph
import app.shoalwatch.ui.home.HomeActions
import app.shoalwatch.ui.home.HomeScreen
import app.shoalwatch.ui.home.HomeViewModel
import app.shoalwatch.ui.shell.AppShell
import app.shoalwatch.ui.shell.DownloadsScreen
import app.shoalwatch.ui.shell.Tab

/*
 * The browsing routes: one small composable per route, as in Routes.kt. A tab goes to the same place whichever tab you are
 * on, keeping each tab's own state (scroll, query) and always having Home underneath, so Back from Search or Downloads goes
 * to Home and Back from Home leaves the app.
 */

internal fun NavHostController.switchTab(tab: Tab) {
    val route = when (tab) {
        Tab.HOME -> Routes.home()
        Tab.DOWNLOADS -> Routes.DOWNLOADS
    }
    navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
internal fun HomeRoute(graph: AppGraph, nav: NavHostController, fresh: Boolean) {
    val vm = graphViewModel { HomeViewModel(graph.serverCatalog, graph.addressSelector, graph.library, graph.playstate, refreshOnOpen = fresh) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val actions = remember(vm, nav) {
        HomeActions(
            onOpenServers = { nav.navigate(Routes.SERVERS) },
            onSwitchAccount = vm::switchAccount,
            onRefresh = vm::refresh,
            onRetryConnection = vm::retry,
            onOpenLibrary = {},
            onOpen = {},
            onSignIn = { nav.navigate(Routes.signIn(it)) },
            onReview = { nav.navigate(Routes.serverEdit(it)) },
            onRemoveFromResume = vm::removeFromResume,
            onSetPlayed = vm::setPlayed,
            onSetFavourite = vm::setFavourite,
        )
    }
    AppShell(Tab.HOME, nav::switchTab, snackbar) { HomeScreen(state, actions, vm.messages, snackbar) }
}

@Composable
internal fun DownloadsRoute(graph: AppGraph, nav: NavHostController) {
    val snackbar = remember { SnackbarHostState() }
    AppShell(Tab.DOWNLOADS, nav::switchTab, snackbar) { DownloadsScreen() }
}
