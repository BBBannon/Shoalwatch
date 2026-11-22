package app.shoalwatch.ui.nav

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.shoalwatch.AppGraph
import app.shoalwatch.data.playback.StartFrom

/* The playlist and photo routes (Milestone 11), one small composable per route as in BrowseRoutes.kt. */

/** The profile's playlists (Milestone 11): not a tab, reached from Home's Playlists card. */
@Composable
internal fun PlaylistsRoute(graph: AppGraph, nav: NavHostController) {
    val vm = graphViewModel { app.shoalwatch.ui.lists.PlaylistsViewModel(graph.serverCatalog, graph.live.lists) }
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

/** The full-screen photo viewer (Milestone 11): the folder's photos in a pager, starting at the tapped one. Close leaves once, however often it is tapped. */
@Composable
internal fun PhotoRoute(graph: AppGraph, nav: NavHostController, photoId: String, parentId: String?) {
    val vm = graphViewModel(key = "$photoId|$parentId") { app.shoalwatch.ui.photos.PhotoViewerViewModel(photoId, parentId, graph.serverCatalog, graph.live.discover, graph.library) }
    val state by vm.state.collectAsStateWithLifecycle()
    val actions = remember(vm, nav) {
        app.shoalwatch.ui.photos.PhotoViewerActions(
            onClose = { vm.once { nav.popBackStack() } }, onRetry = vm::retry, onOpenServers = { nav.navigate(Routes.SERVERS) },
        )
    }
    app.shoalwatch.ui.photos.PhotoViewerScreen(state, actions)
}

/** One playlist (Milestone 11). Play and Shuffle open the player carrying the queue's token; deleting it leaves the screen. */
@Composable
internal fun PlaylistRoute(graph: AppGraph, nav: NavHostController, playlistId: String) {
    val vm = graphViewModel(key = playlistId) { app.shoalwatch.ui.lists.PlaylistViewModel(playlistId, graph.serverCatalog, graph.live.lists, graph.live.playQueues, graph.downloads) }
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm, nav) { vm.launches.collect { nav.navigate(Routes.player(it.itemId, StartFrom.BEGINNING, it.token)) } }
    LaunchedEffect(state.deleted) { if (state.deleted) nav.popBackStack() }
    androidx.lifecycle.compose.LifecycleResumeEffect(vm) {
        vm.onResumed()
        vm.refresh() // an entry's page may have added to or removed from this playlist
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
