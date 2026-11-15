package app.shoalwatch.ui.nav

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.shoalwatch.AppGraph
import app.shoalwatch.data.playback.PlaybackState
import app.shoalwatch.ui.player.HostLifecycle
import app.shoalwatch.ui.player.PlayerActions
import app.shoalwatch.ui.player.PlayerScreen
import app.shoalwatch.ui.player.PlayerViewModel
import app.shoalwatch.ui.player.PlayerWindow
import app.shoalwatch.ui.player.TouchExploration
import app.shoalwatch.ui.player.VideoSurface
import app.shoalwatch.ui.player.rememberDolbyVisionScreen
import app.shoalwatch.ui.home.HomeActions
import app.shoalwatch.ui.home.HomeScreen
import app.shoalwatch.ui.home.HomeViewModel
import app.shoalwatch.ui.components.DismissOnSessionChange
import app.shoalwatch.ui.detail.DetailActions
import app.shoalwatch.ui.detail.DetailScreen
import app.shoalwatch.ui.detail.DetailViewModel
import app.shoalwatch.ui.library.LibraryGridActions
import app.shoalwatch.ui.library.LibraryGridScreen
import app.shoalwatch.ui.library.LibraryGridViewModel
import app.shoalwatch.ui.present.Destination
import app.shoalwatch.ui.search.SearchActions
import app.shoalwatch.ui.search.SearchScreen
import app.shoalwatch.ui.search.SearchViewModel
import app.shoalwatch.ui.series.SeriesActions
import app.shoalwatch.ui.series.SeriesScreen
import app.shoalwatch.ui.series.SeriesViewModel
import app.shoalwatch.ui.shell.AppShell
import app.shoalwatch.ui.downloads.DownloadsActions
import app.shoalwatch.ui.downloads.DownloadsScreen
import app.shoalwatch.ui.downloads.DownloadsViewModel
import app.shoalwatch.ui.downloads.OfflineSeriesActions
import app.shoalwatch.ui.downloads.OfflineSeriesScreen
import app.shoalwatch.ui.downloads.OfflineSeriesViewModel
import app.shoalwatch.data.playback.StartFrom
import app.shoalwatch.ui.shell.Tab

/*
 * The browsing routes: one small composable per route, as in Routes.kt. A tab goes to the same place whichever tab you are
 * on, keeping each tab's own state (scroll, query) and always having Home underneath, so Back from Search or Downloads goes
 * to Home and Back from Home leaves the app.
 */

internal fun NavHostController.switchTab(tab: Tab) {
    val route = when (tab) {
        Tab.HOME -> Routes.home()
        Tab.SEARCH -> Routes.SEARCH
        Tab.DOWNLOADS -> Routes.DOWNLOADS
    }
    navigate(route) {
        popUpTo(Routes.HOME) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Where tapping an item goes. */
internal fun NavHostController.open(destination: Destination) {
    when (destination) {
        is Destination.Library -> navigate(Routes.library(destination.parentId))
        is Destination.Series -> navigate(Routes.series(destination.seriesId, destination.seasonId, destination.episodeId))
        is Destination.Detail -> navigate(Routes.item(destination.itemId))
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
            onOpenLibrary = { nav.navigate(Routes.library(it)) },
            onOpen = nav::open,
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
internal fun LibraryRoute(graph: AppGraph, nav: NavHostController, viewId: String) {
    val vm = graphViewModel(key = viewId) {
        LibraryGridViewModel(viewId, graph.serverCatalog, graph.addressSelector, graph.library, graph.playstate, graph.browseMemory)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val actions = remember(vm, nav) {
        LibraryGridActions(
            onBack = { nav.popBackStack() },
            onSetSort = vm::setSort,
            onSetFilter = vm::setFilter,
            onClearFilters = vm::clearFilters,
            onEndVisible = vm::onEndVisible,
            onRefresh = vm::refresh,
            onOpen = nav::open,
            onSignIn = { nav.navigate(Routes.signIn(it)) },
            onReview = { nav.navigate(Routes.serverEdit(it)) },
            onOpenServers = { nav.navigate(Routes.SERVERS) },
            onSetPlayed = vm::setPlayed,
            onSetFavourite = vm::setFavourite,
        )
    }
    LibraryGridScreen(state, actions, vm.messages, snackbar)
}

@Composable
internal fun SeriesRoute(graph: AppGraph, nav: NavHostController, seriesId: String, season: String?, episode: String?) {
    val vm = graphStateViewModel(key = seriesId) { saved ->
        SeriesViewModel(seriesId, season, episode, saved, graph.serverCatalog, graph.addressSelector, graph.library, graph.playstate)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val actions = remember(vm, nav) {
        SeriesActions(
            onBack = { nav.popBackStack() },
            onPickSeason = vm::pickSeason,
            onRefresh = vm::refresh,
            onOpen = nav::open,
            onSignIn = { nav.navigate(Routes.signIn(it)) },
            onReview = { nav.navigate(Routes.serverEdit(it)) },
            onOpenServers = { nav.navigate(Routes.SERVERS) },
            onSetSeriesPlayed = vm::setSeriesPlayed,
            onSetSeriesFavourite = vm::setSeriesFavourite,
            onSetSeasonPlayed = vm::setSeasonPlayed,
            onSetPlayed = vm::setPlayed,
            onSetFavourite = vm::setFavourite,
        )
    }
    SeriesScreen(state, actions, vm.messages, snackbar)
}

@Composable
internal fun DetailRoute(graph: AppGraph, nav: NavHostController, itemId: String) {
    val vm = graphViewModel(key = itemId) { DetailViewModel(itemId, graph.serverCatalog, graph.addressSelector, graph.library, graph.playstate) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val actions = remember(vm, nav) {
        DetailActions(
            onBack = { nav.popBackStack() },
            onRefresh = vm::refresh,
            onOpen = nav::open,
            onSignIn = { nav.navigate(Routes.signIn(it)) },
            onReview = { nav.navigate(Routes.serverEdit(it)) },
            onOpenServers = { nav.navigate(Routes.SERVERS) },
            onSetPlayed = vm::setPlayed,
            onSetFavourite = vm::setFavourite,
            onPlay = { from -> nav.navigate(Routes.player(itemId, from)) },
        )
    }
    DetailScreen(state, actions, vm.messages, snackbar)
}

@Composable
internal fun SearchRoute(graph: AppGraph, nav: NavHostController) {
    val vm = graphViewModel { SearchViewModel(graph.serverCatalog, graph.library, graph.addressSelector) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val actions = remember(vm, nav) {
        SearchActions(
            onQueryChange = vm::onQueryChange,
            onClear = vm::clear,
            onRetry = vm::retry,
            onOpen = nav::open,
            onOpenServers = { nav.navigate(Routes.SERVERS) },
        )
    }
    // Search shows no messages of its own, but the snackbar is the shell's: another tab's message does not outlive the account.
    DismissOnSessionChange(snackbar, state.session)
    AppShell(Tab.SEARCH, nav::switchTab, snackbar) { SearchScreen(state, actions) }
}

@Composable
internal fun DownloadsRoute(graph: AppGraph, nav: NavHostController) {
    val vm = graphViewModel { DownloadsViewModel(graph.serverCatalog, graph.addressSelector, graph.downloads) { s -> graph.downloadHousekeeping.reconfirm(s) } }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val actions = remember(vm, nav) {
        DownloadsActions(
            onOpenItem = { nav.navigate(Routes.item(it)) },
            onOpenSeries = { nav.navigate(Routes.downloadedSeries(it)) },
            onSignIn = { nav.navigate(Routes.signIn(it)) },
            onPause = vm::pause, onResume = vm::resume, onRetry = vm::retry,
            onAskRemove = vm::askRemove, onConfirmRemove = vm::confirmRemove, onCancelRemove = vm::cancelRemove,
            onPauseAll = vm::pauseAll, onResumeAll = vm::resumeAll, onTryAgain = vm::tryAgain,
        )
    }
    AppShell(Tab.DOWNLOADS, nav::switchTab, snackbar) { DownloadsScreen(state, actions, vm.messages, snackbar) }
}

@Composable
internal fun OfflineSeriesRoute(graph: AppGraph, nav: NavHostController, seriesId: String) {
    val vm = graphViewModel(key = "downloads:$seriesId") { OfflineSeriesViewModel(seriesId, graph.serverCatalog, graph.downloads) }
    val state by vm.state.collectAsStateWithLifecycle()
    val actions = remember(nav) {
        OfflineSeriesActions(onBack = { nav.popBackStack() }, onOpenItem = { nav.navigate(Routes.item(it)) }, onPlay = { nav.navigate(Routes.player(it, StartFrom.RESUME)) })
    }
    OfflineSeriesScreen(state, actions)
}

/**
 * The player. Its ViewModel (and so the controller and the engine) lives as long as this back-stack entry, so a rotation does not
 * rebuffer. It leaves as soon as the session ends or the controller closes: popped back to where Play was pressed (and if the
 * session changed, `ClearBrowsingOnSessionChange` takes the stack to Home as for any other screen). Popping by route does
 * nothing if that has already taken the player away.
 */
@Composable
internal fun PlayerRoute(graph: AppGraph, nav: NavHostController, itemId: String, from: StartFrom) {
    val vm = graphStateViewModel(key = "$itemId|$from") { saved ->
        PlayerViewModel(itemId, from, saved, graph.serverCatalog, { s, scope -> graph.playbackController(s, scope) { true } }, graph.subtitleSettings, graph.qualityDefault, graph.addressSelector::refreshIfOffline)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.leave) { if (state.leave) nav.popBackStack(Routes.PLAYER, inclusive = true) }
    val playing = state.playback.phase == PlaybackState.Phase.PLAYING || state.playback.phase == PlaybackState.Phase.BUFFERING
    PlayerWindow(keepOn = playing, locked = state.locked)
    HostLifecycle(onStopped = vm::onHostStopped, onStarted = vm::onHostStarted)
    TouchExploration(vm::setTouchExploration)
    val actions = remember(vm, nav) {
        PlayerActions(
            onBack = { nav.popBackStack(Routes.PLAYER, inclusive = true) },
            onTapSurface = vm::tapSurface,
            onPlayPause = vm::playPause,
            onSeek = vm::seekTo,
            onSkipBack = { vm.skipBy(-SKIP_MS) },
            onSkipForward = { vm.skipBy(SKIP_MS) },
            onDoubleTap = { forward -> vm.skipBy(if (forward) SKIP_MS else -SKIP_MS) },
            onPrevious = vm::previous,
            onNext = vm::next,
            onSkipSegment = vm::skipSegment,
            onPlayUpNext = vm::playUpNext,
            onCancelUpNext = vm::cancelUpNext,
            onToggleLock = vm::toggleLock,
            onOpenSheet = vm::openSheet,
            onCloseSheet = vm::closeSheet,
            onSelectAudio = vm::selectAudio,
            onSelectSubtitle = vm::selectSubtitle,
            onBurnIn = vm::burnIn,
            onQuality = vm::setQuality,
            onSpeed = vm::setSpeed,
            onSleep = vm::setSleep,
            onAppearance = vm::setAppearance,
            onDelay = vm::setDelay,
            onRetry = vm::retry,
            onSignIn = { vm.state.value.session?.let { nav.navigate(Routes.signIn(it.serverId)) } },
            onDismissSubtitleNotice = vm::dismissSubtitleNotice,
            onDismissSleepFired = vm::dismissSleepFired,
        )
    }
    PlayerScreen(state, actions, rememberDolbyVisionScreen()) { controlsUp ->
        VideoSurface(vm.video, vm.cues, state.appearance, controlsUp)
    }
}

private const val SKIP_MS = 10_000L
