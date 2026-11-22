package app.shoalwatch.ui.nav

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.shoalwatch.AppGraph
import app.shoalwatch.data.playback.PlaybackState
import android.content.pm.PackageManager
import androidx.compose.ui.platform.LocalContext
import app.shoalwatch.ui.components.LocalCovered
import app.shoalwatch.data.playback.MediaPaths
import app.shoalwatch.ui.player.HostLifecycle
import app.shoalwatch.ui.player.LocalPipHost
import app.shoalwatch.ui.player.PipEffect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import app.shoalwatch.ui.downloads.DownloadControls
import app.shoalwatch.ui.downloads.DownloadSheetHost
import app.shoalwatch.ui.downloads.DownloadsScreen
import app.shoalwatch.ui.downloads.DownloadsViewModel
import app.shoalwatch.ui.downloads.OfflineSeriesActions
import app.shoalwatch.ui.downloads.OfflineSeriesScreen
import app.shoalwatch.ui.downloads.OfflineSeriesViewModel
import app.shoalwatch.data.playback.StartFrom
import app.shoalwatch.ui.shell.Tab
import app.shoalwatch.ui.browse.BulkHost
import app.shoalwatch.ui.present.sheetTitle
import app.shoalwatch.ui.browse.BulkViewModel

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
        is Destination.Playlist -> navigate(Routes.playlist(destination.playlistId))
        is Destination.Photo -> navigate(Routes.photo(destination.photoId, destination.parentId))
    }
}

@Composable
internal fun HomeRoute(graph: AppGraph, nav: NavHostController, fresh: Boolean) {
    val vm = graphViewModel {
        HomeViewModel(
            graph.serverCatalog, graph.addressSelector, graph.library, graph.playstate, refreshOnOpen = fresh,
            switcher = graph.lockParts.switcher, protection = graph.lockParts.profileLocks.protectedProfiles,
        )
    }
    // Round 3: back on Home (from another screen, or the app back in front), its lists are asked again where they are due.
    LifecycleStartEffect(vm) {
        vm.onStarted()
        onStopOrDispose {}
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val prompt by vm.switchPrompt.collectAsStateWithLifecycle()
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
            onSubmitSwitch = vm::submitSwitch,
            onDismissSwitch = vm::dismissSwitch,
            onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            onOpenPlaylists = { nav.navigate(Routes.PLAYLISTS) { launchSingleTop = true } },
        )
    }
    val bulk = rememberBulkHost(graph, nav)
    AppShell(Tab.HOME, nav::switchTab, snackbar) { HomeScreen(state, actions, vm.messages, snackbar, prompt, vm.switchPin, bulk) }
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
    LibraryGridScreen(state, actions, vm.messages, snackbar, rememberBulkHost(graph, nav))
}


@Composable
internal fun SeriesRoute(graph: AppGraph, nav: NavHostController, seriesId: String, season: String?, episode: String?) {
    val vm = graphStateViewModel(key = seriesId) { saved ->
        SeriesViewModel(seriesId, season, episode, saved, graph.serverCatalog, graph.addressSelector, graph.library, graph.playstate, graph.downloads)
    }
    val statuses by vm.downloadStatus.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val bulk = rememberBulkHost(graph, nav)
    val canManage = bulk.state.canManage
    val actions = remember(vm, nav, canManage) {
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
            onManage = if (canManage) ({ nav.navigate(app.shoalwatch.ui.manage.ManageRoutes.hub(seriesId)) { launchSingleTop = true } }) else null,
        )
    }
    val download = DownloadControls(
        statuses, onDownload = vm::downloadEpisode, onRetry = vm::retryDownload, onOpenDownloads = { nav.switchTab(Tab.DOWNLOADS) },
        onSeason = vm::downloadSeason, onSeries = vm::downloadSeries, onUnwatched = vm::downloadUnwatched,
    )
    SeriesScreen(state, actions, vm.messages, snackbar, download, bulk)
    DownloadSheetHost(vm.downloadActions, state.session, { graph.downloadPrefs.notificationAsked }, graph.downloadPrefs::markNotificationAsked)
}

@Composable
internal fun DetailRoute(graph: AppGraph, nav: NavHostController, itemId: String) {
    val vm = graphViewModel(key = itemId) { DetailViewModel(itemId, graph.serverCatalog, graph.addressSelector, graph.library, graph.playstate, graph.downloads) }
    val statuses by vm.downloadStatus.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val bulk = rememberBulkHost(graph, nav)
    val strings = app.shoalwatch.ui.present.rememberStrings()
    val shown = state.item?.item
    val canManage = bulk.state.canManage
    val canDelete = bulk.state.canDelete && !canManage && shown != null
    app.shoalwatch.ui.components.BulkNoticesEffect(bulk.notices, snackbar)
    bulk.state.deleteAsk?.let { ask -> app.shoalwatch.ui.components.DeleteFromServerDialog(ask, bulk.onKeepCopy, bulk.onConfirmDelete, bulk.onDismissDelete, busy = bulk.state.running) }
    val picker = bulk.picker
    if (picker != null) app.shoalwatch.ui.lists.ListPickerEffects(picker, snackbar)
    val actions = remember(vm, nav, canManage, canDelete, shown, strings, picker) {
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
            onManage = if (canManage) ({ nav.navigate(app.shoalwatch.ui.manage.ManageRoutes.hub(itemId)) { launchSingleTop = true } }) else null,
            onDelete = if (canDelete && shown != null) ({
                bulk.onApply(listOf(app.shoalwatch.ui.browse.BulkItem(shown.id, shown.sheetTitle(strings), shown.kind, shown.seriesId)), app.shoalwatch.ui.browse.BulkAction.DELETE_FROM_SERVER)
            }) else null,
            onAddToPlaylist = if (picker != null && shown != null) ({
                picker.onOpen(listOf(app.shoalwatch.ui.browse.BulkItem(shown.id, shown.sheetTitle(strings), shown.kind, shown.seriesId)), app.shoalwatch.ui.lists.ListKind.PLAYLIST)
            }) else null,
            onAddToCollection = if (picker != null && shown != null) ({
                picker.onOpen(listOf(app.shoalwatch.ui.browse.BulkItem(shown.id, shown.sheetTitle(strings), shown.kind, shown.seriesId)), app.shoalwatch.ui.lists.ListKind.COLLECTION)
            }) else null,
        )
    }
    val film = state.item?.item?.kind == app.shoalwatch.data.library.ItemKind.MOVIE
    val download = DownloadControls(statuses, onDownload = { vm.download(film) }, onRetry = { vm.retryDownload() }, onOpenDownloads = { nav.switchTab(Tab.DOWNLOADS) })
    DetailScreen(state, actions, vm.messages, snackbar, download)
    DownloadSheetHost(vm.downloadActions, state.session, { graph.downloadPrefs.notificationAsked }, graph.downloadPrefs::markNotificationAsked)
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
    val bulk = rememberBulkHost(graph, nav)
    AppShell(Tab.SEARCH, nav::switchTab, snackbar) { SearchScreen(state, actions, bulk, snackbar) }
}

@Composable
internal fun DownloadsRoute(graph: AppGraph, nav: NavHostController) {
    val vm = graphViewModel { DownloadsViewModel(graph.serverCatalog, graph.addressSelector, graph.downloads, graph.sync) { s -> graph.sharingRecheck.request(s)?.join() } }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.opened() }
    val snackbar = remember { SnackbarHostState() }
    val actions = remember(vm, nav) {
        DownloadsActions(
            onOpenItem = { nav.navigate(Routes.item(it)) },
            onOpenSeries = { nav.navigate(Routes.downloadedSeries(it)) },
            onSignIn = { nav.navigate(Routes.signIn(it)) },
            onPause = vm::pause, onResume = vm::resume, onRetry = vm::retry,
            onAskRemove = vm::askRemove, onConfirmRemove = vm::confirmRemove, onCancelRemove = vm::cancelRemove,
            onPauseAll = vm::pauseAll, onResumeAll = vm::resumeAll, onTryAgain = vm::tryAgain, onAskRemoveSeries = vm::askRemoveSeries,
            onOpenServers = { nav.navigate(Routes.SERVERS) },
            onAskRemoveMany = vm::askRemoveMany,
        )
    }
    val bulk = rememberBulkHost(graph, nav, canDownload = false)
    AppShell(Tab.DOWNLOADS, nav::switchTab, snackbar) { DownloadsScreen(
            state, actions, vm.messages, snackbar, onOpenSettings = { nav.navigate(Routes.DOWNLOAD_SETTINGS) }, onOpenStorage = { nav.navigate(Routes.STORAGE) },
            bulk = bulk,
        )
    }
}

@Composable
internal fun StorageRoute(graph: AppGraph, nav: NavHostController) {
    val vm = graphViewModel { app.shoalwatch.ui.downloads.StorageViewModel(graph.serverCatalog, graph.housekeeping, graph.downloads) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val actions = remember(vm, nav) {
        app.shoalwatch.ui.downloads.StorageActions(
            onBack = { nav.popBackStack() }, onDeleteWatched = vm::askDeleteWatched, onDeleteSeries = vm::askDeleteSeries, onDeleteFilm = vm::askDeleteFilm,
            onConfirm = vm::confirm, onCancel = vm::cancel, onTryAgain = vm::tryAgain, onOpenServers = { nav.navigate(Routes.SERVERS) },
        )
    }
    // On the theme's surface, as AppScreen's screens are: without it the dark theme drew black text on the dark window (device run W-6).
    androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
        app.shoalwatch.ui.downloads.StorageScreen(state, actions, vm.messages, snackbar)
        androidx.compose.material3.SnackbarHost(snackbar, androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter).navigationBarsPadding())
    } }
}

@Composable
internal fun DownloadSettingsRoute(graph: AppGraph, nav: NavHostController) {
    val vm = graphViewModel {
        app.shoalwatch.ui.downloads.DownloadSettingsViewModel(graph.serverCatalog, graph.downloads, graph.housekeeping, graph.lockParts.householdGuard)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val grownUp by vm.grownUpAsk.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshCards() }
    val actions = remember(vm, nav) {
        app.shoalwatch.ui.downloads.DownloadSettingsActions(
            onBack = { nav.popBackStack() }, onMobileData = vm::setMobileData, onStorage = vm::setStorage, onQuality = vm::setDefaultQuality, onSharing = vm::setSharing,
            onAfterFinish = vm::setAfterFinish, onDeleteShared = vm::setDeleteShared, onAutoNext = vm::setAutoNext,
            onChooseGrownUp = vm::chooseGrownUp, onSubmitGrownUp = vm::submitGrownUp, onDismissGrownUp = vm::dismissGrownUp,
        )
    }
    app.shoalwatch.ui.downloads.DownloadSettingsScreen(state, actions, grownUp = grownUp, grownUpPin = vm.grownUpPin)
}

@Composable
internal fun OfflineSeriesRoute(graph: AppGraph, nav: NavHostController, seriesId: String) {
    val vm = graphViewModel(key = "downloads:$seriesId") { OfflineSeriesViewModel(seriesId, graph.serverCatalog, graph.downloads) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val actions = remember(vm, nav) {
        OfflineSeriesActions(
            onBack = { nav.popBackStack() }, onOpenItem = { nav.navigate(Routes.item(it)) }, onPlay = { nav.navigate(Routes.player(it, StartFrom.RESUME)) },
            onAskRemove = vm::askRemove, onAskRemoveSeason = vm::askRemoveSeason, onConfirmRemove = vm::confirmRemove, onCancelRemove = vm::cancelRemove,
            onOpenServers = { nav.navigate(Routes.SERVERS) },
        )
    }
    // On the theme's surface (device run W-6, as StorageRoute).
    androidx.compose.material3.Surface(androidx.compose.ui.Modifier.fillMaxSize()) { androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
        OfflineSeriesScreen(state, actions, vm.messages, snackbar)
        androidx.compose.material3.SnackbarHost(snackbar, androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter).navigationBarsPadding())
    } }
}

/** The profile's playlists (Milestone 11): not a tab, reached from Home's Playlists card. */
@Composable
internal fun PlaylistsRoute(graph: AppGraph, nav: NavHostController) {
    val vm = graphViewModel { app.shoalwatch.ui.lists.PlaylistsViewModel(graph.serverCatalog, graph.lists) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    androidx.lifecycle.compose.LifecycleResumeEffect(vm) {
        vm.refresh()
        onPauseOrDispose {}
    }
    val actions = remember(vm, nav) {
        app.shoalwatch.ui.lists.PlaylistsActions(
            onBack = { nav.popBackStack() }, onOpen = nav::open, onRetry = vm::retry, onNew = vm::startCreate, onCreate = vm::create,
            onCancelCreate = vm::cancelCreate, onOpenServers = { nav.navigate(Routes.SERVERS) },
        )
    }
    app.shoalwatch.ui.lists.PlaylistsScreen(state, actions, vm.name, vm.notices, snackbar)
}

/** One playlist (Milestone 11). Play and Shuffle open the player carrying the queue's token; deleting it leaves the screen. */
@Composable
internal fun PlaylistRoute(graph: AppGraph, nav: NavHostController, playlistId: String) {
    val vm = graphViewModel(key = playlistId) { app.shoalwatch.ui.lists.PlaylistViewModel(playlistId, graph.serverCatalog, graph.lists, graph.playQueues, graph.downloads) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm, nav) { vm.launches.collect { nav.navigate(Routes.player(it.itemId, StartFrom.BEGINNING, it.token)) } }
    LaunchedEffect(state.deleted) { if (state.deleted) nav.popBackStack() }
    androidx.lifecycle.compose.LifecycleResumeEffect(vm) {
        vm.onResumed()
        onPauseOrDispose {}
    }
    val actions = remember(vm, nav) {
        app.shoalwatch.ui.lists.PlaylistActions(
            onBack = { nav.popBackStack() }, onOpen = nav::open, onRetry = vm::retry,
            onPlay = { vm.play(shuffle = false) }, onShuffle = { vm.play(shuffle = true) }, onDownloadAll = vm::downloadAll,
            onMove = vm::move, onRemove = vm::remove, onAskRename = vm::startRename, onRename = vm::rename, onCancelRename = vm::cancelRename,
            onAskDelete = vm::askDelete, onConfirmDelete = vm::confirmDelete, onCancelDelete = vm::cancelDelete,
            onOpenServers = { nav.navigate(Routes.SERVERS) },
        )
    }
    app.shoalwatch.ui.lists.PlaylistScreen(state, actions, vm.name, vm.notices, snackbar)
}

/**
 * The player. Its ViewModel (and so the controller and the engine) lives as long as this back-stack entry, so a rotation does not
 * rebuffer. It leaves as soon as the session ends or the controller closes: popped back to where Play was pressed (and if the
 * session changed, `ClearBrowsingOnSessionChange` takes the stack to Home as for any other screen). Popping by route does
 * nothing if that has already taken the player away.
 */
@Composable
internal fun PlayerRoute(graph: AppGraph, nav: NavHostController, itemId: String, from: StartFrom, queueToken: String? = null, sourceId: String? = null) {
    val vm = graphStateViewModel(key = "$itemId|$from|$queueToken|$sourceId") { saved ->
        PlayerViewModel(itemId, from, saved, graph.serverCatalog, { s, scope -> graph.playbackController(s, scope, graph.lockParts.profilePlayback.autoplayReader(s, scope)) }, graph.subtitleSettings, graph.qualityDefault, graph.addressSelector::refreshIfOffline, graph.housekeeping,
            { s -> graph.playQueues.get(s, queueToken) }, sourceId?.takeIf(MediaPaths::isSourceId),
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.leave) { if (state.leave) nav.popBackStack(Routes.PLAYER, inclusive = true) }
    val playing = state.playback.phase == PlaybackState.Phase.PLAYING || state.playback.phase == PlaybackState.Phase.BUFFERING
    PlayerWindow(keepOn = playing, locked = state.locked)
    HostLifecycle(onStopped = vm::onHostStopped, onStarted = vm::onHostStarted)
    TouchExploration(vm::setTouchExploration)
    // Picture-in-picture (Milestone 7): wished for while this player shows and nothing covers it; the small window's button plays and pauses.
    val pip = LocalPipHost.current?.takeIf { LocalContext.current.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }
    val inPip by (pip?.inPip ?: NotInPip).collectAsStateWithLifecycle()
    val aspect by (vm.video?.aspectRatio ?: NoAspect).collectAsStateWithLifecycle()
    PipEffect(state, enabled = !LocalCovered.current, onPlayPause = vm::playPause, aspect = aspect)
    val actions = remember(vm, nav, pip) {
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
            onWatchedDelete = vm::watchedDelete,
            onWatchedTogether = vm::watchedTogether,
            onKeepDownload = vm::keepDownload,
            onDismissWatchedMessage = vm::dismissWatchedMessage,
            onSubmitSharerPin = vm::submitSharerPin,
            onCancelSharerPin = vm::cancelSharerPin,
            onEnterPip = pip?.let { host -> { host.enterNow(); Unit } },
        )
    }
    PlayerScreen(state, actions, rememberDolbyVisionScreen(), inPip, sharerPin = vm.sharerPin) { controlsUp ->
        VideoSurface(vm.video, vm.cues, state.appearance, controlsUp)
    }
}

private const val SKIP_MS = 10_000L
private val NotInPip: StateFlow<Boolean> = MutableStateFlow(false)
private val NoAspect: StateFlow<Float?> = MutableStateFlow(null)
