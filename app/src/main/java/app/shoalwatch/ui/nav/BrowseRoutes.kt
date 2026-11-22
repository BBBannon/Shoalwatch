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
            onOpenFilter = { nav.navigate(Routes.browse(viewId)) },
            onOpenGenres = { nav.navigate(Routes.genres(viewId)) },
            onOpenStudios = { nav.navigate(Routes.studios(viewId)) },
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
    val extras = rememberExtras(graph, nav, seriesId, snackbar).controls
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
    SeriesScreen(state, actions, vm.messages, snackbar, download, bulk, extras)
    DownloadSheetHost(vm.downloadActions, state.session, { graph.downloadPrefs.notificationAsked }, graph.downloadPrefs::markNotificationAsked)
}

@Composable
internal fun DetailRoute(graph: AppGraph, nav: NavHostController, itemId: String) {
    val vm = graphViewModel(key = itemId) { DetailViewModel(itemId, graph.serverCatalog, graph.addressSelector, graph.library, graph.playstate, graph.downloads, graph.live.remoteSubtitles) }
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
    val extras = rememberExtras(graph, nav, itemId, snackbar)
    val actions = remember(vm, nav, canManage, canDelete, shown, strings, picker, extras.vm) {
        DetailActions(
            onBack = { nav.popBackStack() },
            onRefresh = vm::refresh,
            onOpen = nav::open,
            onSignIn = { nav.navigate(Routes.signIn(it)) },
            onReview = { nav.navigate(Routes.serverEdit(it)) },
            onOpenServers = { nav.navigate(Routes.SERVERS) },
            onSetPlayed = vm::setPlayed,
            onSetFavourite = vm::setFavourite,
            // The version picked is for this play only (taken, so it is cleared); none picked is the server's first.
            onPlay = { from -> extras.vm.once { nav.navigate(Routes.player(itemId, from, source = extras.vm.takeVersion())) } },
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
            onFindSubtitles = vm.subtitleSearch?.actions?.onOpen,
        )
    }
    val film = state.item?.item?.kind == app.shoalwatch.data.library.ItemKind.MOVIE
    val download = DownloadControls(statuses, onDownload = { vm.download(film) }, onRetry = { vm.retryDownload() }, onOpenDownloads = { nav.switchTab(Tab.DOWNLOADS) })
    val search = vm.subtitleSearch
    val searchUi = search?.state?.collectAsStateWithLifecycle()?.value
    val subtitles = if (search != null && searchUi != null) app.shoalwatch.ui.subtitles.SubtitleSearchControls(searchUi, search.actions) else null
    DetailScreen(state, actions, vm.messages, snackbar, download, extras.controls, subtitles)
    DownloadSheetHost(vm.downloadActions, state.session, { graph.downloadPrefs.notificationAsked }, graph.downloadPrefs::markNotificationAsked)
}

@Composable
internal fun SearchRoute(graph: AppGraph, nav: NavHostController) {
    val vm = graphViewModel { SearchViewModel(graph.serverCatalog, graph.library, graph.addressSelector, graph.live.discover) }
    val tap = app.shoalwatch.ui.discover.rememberTapOnce()
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val actions = remember(vm, nav, tap) {
        SearchActions(
            onQueryChange = vm::onQueryChange,
            onClear = vm::clear,
            onRetry = vm::retry,
            onOpen = nav.openOnce(tap), // title and collection rows: a double tap opens one page
            onOpenServers = { nav.navigate(Routes.SERVERS) },
            onOpenPerson = { id -> tap.once { nav.navigate(Routes.person(id)) } },
            onOpenGenre = { genre -> tap.once { nav.navigate(Routes.browse(null, genreId = genre)) } },
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
