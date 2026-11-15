package app.shoalwatch.ui.nav

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.shoalwatch.AppGraph
import app.shoalwatch.R

/** Screens and their paths. Server ids go through [Uri.encode] so any id is a safe path segment. */
internal object Routes {
    const val WELCOME = "welcome"
    const val ADD_SERVER = "add_server"
    const val ARG_FRESH = "fresh"

    /** Home (the shell). [ARG_FRESH] is set when it is reached by a successful sign-in, so it asks the server for its rows once. */
    const val HOME = "home?$ARG_FRESH={$ARG_FRESH}"
    const val DOWNLOADS = "downloads"
    const val ARG_VIEW = "viewId"
    const val LIBRARY = "library/{$ARG_VIEW}"
    const val SERVERS = "servers"
    const val ARG_SERVER = "serverId"
    const val SIGN_IN = "sign_in/{$ARG_SERVER}"
    const val SERVER_EDIT = "server/{$ARG_SERVER}"

    fun home(fresh: Boolean = false) = "home?$ARG_FRESH=$fresh"
    fun library(viewId: String) = "library/${Uri.encode(viewId)}"
    fun signIn(serverId: String) = "sign_in/${Uri.encode(serverId)}"
    fun serverEdit(serverId: String) = "server/${Uri.encode(serverId)}"
}

/**
 * The whole UI. A neutral splash shows until the saved sign-ins have loaded and the first screen is known
 * ([StartupViewModel]), so the wrong screen never flashes.
 *
 * Navigation graph: `welcome` (first run) or `home` to start; `add_server` and `sign_in/{serverId}` for
 * onboarding; `servers` and `server/{serverId}` for management. Signing in clears the stack and lands on `home`.
 */
@Composable
fun AppRoot(graph: AppGraph) {
    val startup = graphViewModel { StartupViewModel(graph.serverCatalog) }
    when (startup.state.collectAsStateWithLifecycle().value) {
        StartupState.Loading -> SplashScreen()
        StartupState.Failed -> StartupFailedScreen(onRetry = startup::retry)
        StartupState.Welcome -> AppNavHost(graph, Routes.WELCOME)
        StartupState.Home -> AppNavHost(graph, Routes.HOME)
    }
}

@Composable
private fun SplashScreen() {
    val label = stringResource(R.string.splash_loading)
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { CircularProgressIndicator() }
}

private val TabRoutes = setOf(Routes.HOME, Routes.DOWNLOADS)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.betweenTabs() =
    initialState.destination.route in TabRoutes && targetState.destination.route in TabRoutes

@Composable
private fun AppNavHost(graph: AppGraph, start: String) {
    val nav = rememberNavController()
    val serverArg = listOf(navArgument(Routes.ARG_SERVER) { type = NavType.StringType })
    NavHost(
        navController = nav,
        startDestination = start,
        // Between the tabs (Home, Search, Downloads) there is only a short fade: they are siblings, not a journey.
        enterTransition = { if (betweenTabs()) fadeIn(tween(150)) else fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 16 } },
        exitTransition = { fadeOut(tween(if (betweenTabs()) 100 else 160)) },
        popEnterTransition = { fadeIn(tween(if (betweenTabs()) 150 else 220)) },
        popExitTransition = { if (betweenTabs()) fadeOut(tween(100)) else fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { it / 16 } },
    ) {
        composable(Routes.WELCOME) { AddServerRoute(graph, nav, firstRun = true) }
        composable(Routes.ADD_SERVER) { AddServerRoute(graph, nav, firstRun = false) }
        composable(Routes.SIGN_IN, serverArg) { SignInRoute(graph, nav, it.arguments?.getString(Routes.ARG_SERVER).orEmpty()) }
        composable(Routes.HOME, listOf(navArgument(Routes.ARG_FRESH) { type = NavType.BoolType; defaultValue = false })) {
            HomeRoute(graph, nav, fresh = it.arguments?.getBoolean(Routes.ARG_FRESH) == true)
        }
        composable(Routes.DOWNLOADS) { DownloadsRoute(graph, nav) }
        composable(Routes.LIBRARY, listOf(navArgument(Routes.ARG_VIEW) { type = NavType.StringType })) {
            LibraryRoute(graph, nav, it.arguments?.getString(Routes.ARG_VIEW).orEmpty())
        }
        composable(Routes.SERVERS) { ServersRoute(graph, nav) }
        composable(Routes.SERVER_EDIT, serverArg) { ServerEditRoute(graph, nav, it.arguments?.getString(Routes.ARG_SERVER).orEmpty()) }
    }
}
