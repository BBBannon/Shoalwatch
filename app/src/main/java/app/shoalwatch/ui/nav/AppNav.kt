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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import app.shoalwatch.security.LockState
import app.shoalwatch.ui.shell.Tab
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.background
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import androidx.navigation.navOptions
import app.shoalwatch.AppGraph
import app.shoalwatch.R
import app.shoalwatch.data.playback.StartFrom
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.admin.adminDestinations
import app.shoalwatch.ui.manage.manageDestinations
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import androidx.navigation.NavHostController

/** Screens and their paths. Server ids go through [Uri.encode] so any id is a safe path segment. */
internal object Routes {
    const val WELCOME = "welcome"

    /** The one empty route while the app or a profile is locked (`ResetOnLock`). */
    const val LOCKED = "locked"
    const val ADD_SERVER = "add_server"
    const val ARG_FRESH = "fresh"

    /** Home (the shell). [ARG_FRESH] is set when it is reached by a successful sign-in, so it asks the server for its rows once. */
    const val HOME = "home?$ARG_FRESH={$ARG_FRESH}"
    const val DOWNLOADS = "downloads"
    const val SEARCH = "search"
    const val ARG_VIEW = "viewId"
    const val LIBRARY = "library/{$ARG_VIEW}"
    const val ARG_SERIES = "seriesId"
    const val ARG_SEASON = "season"
    const val ARG_EPISODE = "episode"
    const val SERIES = "series/{$ARG_SERIES}?$ARG_SEASON={$ARG_SEASON}&$ARG_EPISODE={$ARG_EPISODE}"
    const val ARG_ITEM = "itemId"
    const val ITEM = "item/{$ARG_ITEM}"
    const val ARG_FROM = "from"
    /** The player for an item. [ARG_FROM] is "resume" or "start"; anything else (or nothing) arrives as the start. */
    const val PLAYER = "play/{$ARG_ITEM}?$ARG_FROM={$ARG_FROM}"
    const val SERVERS = "servers"
    const val ARG_SERVER = "serverId"
    /** [ARG_USER]: a profile's name to fill in (signing in again after the server rejected its sign-in); optional. */
    const val ARG_USER = "user"
    /** [ARG_USER_ID]: that profile's id; the sign-in must come back as it or nothing changes (`WRONG_ACCOUNT`). Optional. */
    const val ARG_USER_ID = "userId"
    const val SIGN_IN = "sign_in/{$ARG_SERVER}?$ARG_USER={$ARG_USER}&$ARG_USER_ID={$ARG_USER_ID}"
    const val SERVER_EDIT = "server/{$ARG_SERVER}"
    /** One downloaded series, offline (Milestone 4). A malformed id reads as "This is no longer available". */
    const val DOWNLOADED_SERIES = "downloads/series/{$ARG_SERIES}"
    const val DOWNLOAD_SETTINGS = "download_settings"
    /** Settings (Milestone 7): not an account route, so a session change pops it. */
    const val SETTINGS = "settings"
    /** Approve a Quick Connect code (Milestone 10): not an account route. */
    const val QUICK_CONNECT = "settings/quick_connect"
    const val STORAGE = "downloads/storage"

    fun home(fresh: Boolean = false) = "home?$ARG_FRESH=$fresh"
    fun library(viewId: String) = "library/${Uri.encode(viewId)}"

    /** A series, optionally at a season with one episode picked out. Ids are not trusted: the library answers a bad one with "no longer available". */
    /** An absent season or episode is left out altogether: an empty `season=` does not arrive as "no season". */
    fun series(seriesId: String, seasonId: String? = null, episodeId: String? = null): String {
        val query = listOfNotNull(
            seasonId?.takeIf { it.isNotEmpty() }?.let { "$ARG_SEASON=${Uri.encode(it)}" },
            episodeId?.takeIf { it.isNotEmpty() }?.let { "$ARG_EPISODE=${Uri.encode(it)}" },
        )
        return "series/${Uri.encode(seriesId)}" + if (query.isEmpty()) "" else query.joinToString("&", prefix = "?")
    }

    fun item(itemId: String) = "item/${Uri.encode(itemId)}"
    fun downloadedSeries(seriesId: String) = "downloads/series/${Uri.encode(seriesId)}"
    fun player(itemId: String, from: StartFrom) = "play/${Uri.encode(itemId)}?$ARG_FROM=${if (from == StartFrom.RESUME) FROM_RESUME else FROM_START}"
    const val FROM_RESUME = "resume"
    const val FROM_START = "start"
    fun signIn(serverId: String) = "sign_in/${Uri.encode(serverId)}"

    /** "Sign in again" for one profile: its name is filled in and only that account is accepted. */
    fun signInAgain(serverId: String, userId: String, userName: String) =
        "sign_in/${Uri.encode(serverId)}?$ARG_USER=${Uri.encode(userName)}&$ARG_USER_ID=${Uri.encode(userId)}"
    fun serverEdit(serverId: String) = "server/${Uri.encode(serverId)}"
}

/**
 * The whole UI. A neutral splash shows until the saved sign-ins have loaded and the first screen is known
 * ([StartupViewModel]), so the wrong screen never flashes.
 *
 * Navigation graph: `welcome` (first run) or `home` to start; `add_server` and `sign_in/{serverId}` for
 * onboarding; `play/{itemId}?from=` for the player; `servers` and `server/{serverId}` for management. Signing in clears the stack and lands on `home`.
 */
@Composable
fun AppRoot(graph: AppGraph, openTab: String? = null, onTabOpened: () -> Unit = {}) {
    val startup = graphViewModel { StartupViewModel(graph.serverCatalog) }
    val gate = graph.lockParts.lockGate
    // Not paused with the lifecycle, and on Main.immediate: the lock must be current the moment the app is seen again (review M2).
    val lock by gate.state.collectAtOnce()
    val openFor by gate.openFor.collectAtOnce()
    val active = rememberActive(graph.serverCatalog)
    when (val first = startup.state.collectAsStateWithLifecycle().value) {
        StartupState.Loading -> SplashScreen()
        StartupState.Failed -> StartupFailedScreen(onRetry = startup::retry)
        StartupState.Welcome, StartupState.Home -> if (lock == LockState.Checking) {
            SplashScreen()
        } else {
            val start = if (first == StartupState.Welcome) Routes.WELCOME else Routes.HOME
            LockCover(
                openFor, active,
                host = { AppNavHost(graph, start, if (first == StartupState.Home) openTab else null, onTabOpened, lock) },
                cover = { LockRoute(graph, lock) },
            )
        }
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

private val TabRoutes = setOf(Routes.HOME, Routes.SEARCH, Routes.DOWNLOADS)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.betweenTabs() =
    initialState.destination.route in TabRoutes && targetState.destination.route in TabRoutes

@Composable
private fun AppNavHost(graph: AppGraph, start: String, openTab: String?, onTabOpened: () -> Unit, lock: LockState) {
    // Locked from the start: nothing of the profile is ever composed beneath the lock, not even a stack restored after process
    // death (final review M3).
    val nav = rememberNavControllerUnlessLocked { lock.locked }
    val first = remember { if (lock.locked) Routes.LOCKED else start }
    ResetOnLock(nav, lock) { graph.serverCatalog.servers.first().isNotEmpty() }
    // The download notice's tap: the Downloads tab, over Home as every tab is; kept until the app is open.
    LaunchedEffect(openTab, lock == LockState.Open) {
        if (openTab == Routes.DOWNLOADS && lock == LockState.Open) {
            nav.switchTab(Tab.DOWNLOADS)
            onTabOpened()
        }
    }
    ClearBrowsingOnSessionChange(nav, graph.serverCatalog)
    val serverArg = listOf(navArgument(Routes.ARG_SERVER) { type = NavType.StringType })
    NavHost(
        navController = nav,
        startDestination = first,
        // Between the tabs (Home, Search, Downloads) there is only a short fade: they are siblings, not a journey.
        enterTransition = { if (betweenTabs()) fadeIn(tween(150)) else fadeIn(tween(220)) + slideInHorizontally(tween(220)) { it / 16 } },
        exitTransition = { fadeOut(tween(if (betweenTabs()) 100 else 160)) },
        popEnterTransition = { fadeIn(tween(if (betweenTabs()) 150 else 220)) },
        popExitTransition = { if (betweenTabs()) fadeOut(tween(100)) else fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { it / 16 } },
    ) {
        composable(Routes.LOCKED) {}
        composable(Routes.WELCOME) { AddServerRoute(graph, nav, firstRun = true) }
        composable(Routes.ADD_SERVER) { AddServerRoute(graph, nav, firstRun = false) }
        composable(
            Routes.SIGN_IN,
            serverArg + listOf(Routes.ARG_USER, Routes.ARG_USER_ID).map { name -> navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null } },
        ) {
            SignInRoute(
                graph, nav, it.arguments?.getString(Routes.ARG_SERVER).orEmpty(),
                it.arguments?.getString(Routes.ARG_USER), it.arguments?.getString(Routes.ARG_USER_ID),
            )
        }
        composable(Routes.HOME, listOf(navArgument(Routes.ARG_FRESH) { type = NavType.BoolType; defaultValue = false })) {
            HomeRoute(graph, nav, fresh = it.arguments?.getBoolean(Routes.ARG_FRESH) == true)
        }
        composable(Routes.SEARCH) { SearchRoute(graph, nav) }
        composable(Routes.DOWNLOADS) { DownloadsRoute(graph, nav) }
        browsingDestinations(
            library = { viewId -> LibraryRoute(graph, nav, viewId) },
            series = { id, season, episode -> SeriesRoute(graph, nav, id, season, episode) },
            item = { id -> DetailRoute(graph, nav, id) },
            player = { id, from -> PlayerRoute(graph, nav, id, from) },
            downloadedSeries = { id -> OfflineSeriesRoute(graph, nav, id) },
            storage = { StorageRoute(graph, nav) },
        )
        composable(Routes.SERVERS) { ServersRoute(graph, nav) }
        composable(Routes.DOWNLOAD_SETTINGS) { DownloadSettingsRoute(graph, nav) }
        composable(Routes.SETTINGS) { SettingsRoute(graph, nav) }
        composable(Routes.QUICK_CONNECT) { QuickConnectRoute(graph, nav) }
        composable(Routes.SERVER_EDIT, serverArg) { ServerEditRoute(graph, nav, it.arguments?.getString(Routes.ARG_SERVER).orEmpty()) }
        adminDestinations(graph, nav)
        manageDestinations(graph, nav)
    }
}

/** Screens for managing servers and accounts: they show every account, belong to no session, and survive a session change. */
private val AccountRoutes = setOf(Routes.SERVERS, Routes.SERVER_EDIT, Routes.ADD_SERVER, Routes.SIGN_IN)

/**
 * One account's screens never outlive it. When the active session changes from one session to another (an account switch from
 * Home or the Servers screen), or to nobody (sign-out, a removed account or server, a sign-in the server no longer accepts), the
 * whole back stack goes and the app starts again at Home (at Welcome when no server is left, as at start-up). Without this, going
 * Back would show a library, series or item that the old account opened, running as the new one. Nothing is saved for the popped
 * screens (no `saveState`), and the tabs' saved states go too, on every path. The first value (the app starting) and a change
 * FROM nobody (the sign-in flow has just finished and navigates to Home itself) do nothing.
 *
 * If an account screen ([AccountRoutes]) is on top, the stack becomes `[Home or Welcome, that screen]`: the very same entry, with
 * its ViewModel, is kept (popped with `saveState` and restored on top), so a sign-out made there still shows its "signed out
 * here only" notice or its error, and a sign-in in progress (whose store update can arrive before it navigates) still ends on
 * its own fresh Home.
 */
@Composable
internal fun ClearBrowsingOnSessionChange(nav: NavHostController, catalog: ServerCatalog) {
    LaunchedEffect(nav, catalog) {
        var previous: Session? = null
        var started = false
        // The account flow, not `activeSession()`: that one drops a repeated value, and the store's answer once it can be read again
        // must be seen even when it is the same nobody as the fail-closed one.
        catalog.activeAccount.map { a -> a?.let { Session(it.serverId, it.userId) } }.collect { now ->
            // Nobody because the store could not be read is not a sign-out: every screen shows its signed-out state meanwhile (nothing
            // of the session is shown), but the stack stays and the user keeps their place. What the store says once it can be read
            // again (after Retry) is compared with the session from before the failure: the same one moves nothing.
            if (now == null && catalog.readFailed.value) return@collect
            val was = previous
            val first = !started
            started = true
            previous = now
            if (first || was == null || now == was) return@collect
            val target = if (now == null && catalog.servers.first().isEmpty()) Routes.WELCOME else Routes.home()
            // The store's answer can arrive on its own thread, and the navigation controller may only be touched on the main one.
            // The effect's own dispatcher is the main one in the app; this holds it there whatever dispatcher runs the effect.
            withContext(Dispatchers.Main.immediate) {
                // A sign-in as another account (from Servers) has already taken the app to a fresh Home, alone: leave that one be.
                val here = nav.currentBackStackEntry
                @android.annotation.SuppressLint("RestrictedApi") // no public API gives the whole back stack; currentBackStackEntry is the top
                val alone = nav.currentBackStack.value.count { it.destination !is NavGraph } == 1
                if (alone && here?.destination?.route == Routes.HOME && here.arguments?.getBoolean(Routes.ARG_FRESH) == true) {
                    nav.clearTabStates()
                    return@withContext
                }
                val keep = here?.takeIf { it.destination.route in AccountRoutes }
                if (keep != null) nav.popBackStack(keep.destination.id, inclusive = true, saveState = true)
                // Before the new start: clearing a tab restores what it saved and pops it, which may take anything left with it.
                nav.clearTabStates()
                nav.navigate(target) { popUpTo(nav.graph.id) { inclusive = true } }
                if (keep != null) nav.navigate(keep.destination.id, keep.arguments, navOptions { restoreState = true })
            }
        }
    }
}

/** Tabs left earlier were saved with their state (screens opened under Home, the Search query); none of it is kept. */
private fun NavHostController.clearTabStates() = listOf(Routes.HOME, Routes.SEARCH, Routes.DOWNLOADS).forEach { clearBackStack(it) }

/**
 * The library, series and item destinations: their routes, argument names, and how an argument that is not there arrives (as
 * null, never as an empty id). The screens are passed in so a test can drive this exact graph with recorders.
 */
internal fun NavGraphBuilder.browsingDestinations(
    library: @Composable (viewId: String) -> Unit,
    series: @Composable (seriesId: String, seasonId: String?, episodeId: String?) -> Unit,
    item: @Composable (itemId: String) -> Unit,
    player: @Composable (itemId: String, from: StartFrom) -> Unit,
    downloadedSeries: @Composable (seriesId: String) -> Unit,
    storage: @Composable () -> Unit = {},
) {
    composable(Routes.STORAGE) { storage() }
    composable(Routes.DOWNLOADED_SERIES, listOf(navArgument(Routes.ARG_SERIES) { type = NavType.StringType })) {
        downloadedSeries(it.arguments?.getString(Routes.ARG_SERIES).orEmpty())
    }
    composable(Routes.LIBRARY, listOf(navArgument(Routes.ARG_VIEW) { type = NavType.StringType })) {
        library(it.arguments?.getString(Routes.ARG_VIEW).orEmpty())
    }
    composable(
        Routes.SERIES,
        listOf(
            navArgument(Routes.ARG_SERIES) { type = NavType.StringType },
            navArgument(Routes.ARG_SEASON) { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument(Routes.ARG_EPISODE) { type = NavType.StringType; nullable = true; defaultValue = null },
        ),
    ) {
        val args = it.arguments
        series(
            args?.getString(Routes.ARG_SERIES).orEmpty(),
            args?.getString(Routes.ARG_SEASON)?.ifEmpty { null },
            args?.getString(Routes.ARG_EPISODE)?.ifEmpty { null },
        )
    }
    composable(Routes.ITEM, listOf(navArgument(Routes.ARG_ITEM) { type = NavType.StringType })) {
        item(it.arguments?.getString(Routes.ARG_ITEM).orEmpty())
    }
    composable(
        Routes.PLAYER,
        listOf(
            navArgument(Routes.ARG_ITEM) { type = NavType.StringType },
            navArgument(Routes.ARG_FROM) { type = NavType.StringType; nullable = true; defaultValue = null },
        ),
    ) {
        val from = if (it.arguments?.getString(Routes.ARG_FROM) == Routes.FROM_RESUME) StartFrom.RESUME else StartFrom.BEGINNING
        player(it.arguments?.getString(Routes.ARG_ITEM).orEmpty(), from)
    }
}
