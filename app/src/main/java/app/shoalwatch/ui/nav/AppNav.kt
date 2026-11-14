package app.shoalwatch.ui.nav

import android.net.Uri
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
    const val HOME = "home"
    const val SERVERS = "servers"
    const val ARG_SERVER = "serverId"
    const val SIGN_IN = "sign_in/{$ARG_SERVER}"
    const val SERVER_EDIT = "server/{$ARG_SERVER}"

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

@Composable
private fun AppNavHost(graph: AppGraph, start: String) {
    val nav = rememberNavController()
    val serverArg = listOf(navArgument(Routes.ARG_SERVER) { type = NavType.StringType })
    NavHost(
        navController = nav,
        startDestination = start,
        enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 16 } },
        exitTransition = { fadeOut(tween(160)) },
        popEnterTransition = { fadeIn(tween(220)) },
        popExitTransition = { fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { it / 16 } },
    ) {
        composable(Routes.WELCOME) { AddServerRoute(graph, nav, firstRun = true) }
        composable(Routes.ADD_SERVER) { AddServerRoute(graph, nav, firstRun = false) }
        composable(Routes.SIGN_IN, serverArg) { SignInRoute(graph, nav, it.arguments?.getString(Routes.ARG_SERVER).orEmpty()) }
        composable(Routes.HOME) { HomeRoute(graph, nav) }
        composable(Routes.SERVERS) { ServersRoute(graph, nav) }
        composable(Routes.SERVER_EDIT, serverArg) { ServerEditRoute(graph, nav, it.arguments?.getString(Routes.ARG_SERVER).orEmpty()) }
    }
}
