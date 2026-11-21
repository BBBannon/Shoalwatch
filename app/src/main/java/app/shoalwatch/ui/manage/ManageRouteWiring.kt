package app.shoalwatch.ui.manage

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import app.shoalwatch.AppGraph
import app.shoalwatch.ui.nav.graphViewModel
import app.shoalwatch.ui.nav.leaveItemPage

/** The real management destinations, each built from [graph] (see [manageDestinations] for the routes). */
internal fun NavGraphBuilder.manageDestinations(graph: AppGraph, nav: NavHostController) = manageDestinations(
    hub = { ManageHubRoute(graph, nav, it) },
    identify = { IdentifyRoute(graph, nav, it) },
    images = { ImagesRoute(graph, nav, it) },
    edit = { EditRoute(graph, nav, it) },
)

private fun afterChange(graph: AppGraph) = AfterChange(graph.library) { server, item -> graph.artworkEviction.evictItem(server, item) }

@Composable
private fun ManageHubRoute(graph: AppGraph, nav: NavHostController, itemId: String) {
    val vm = graphViewModel(key = "manage-$itemId") {
        ManageViewModel(itemId, graph.serverCatalog, graph.adminRights, graph.addressSelector, graph.manage, graph.library, graph.downloads, afterChange(graph))
    }
    val state by vm.state.collectAsStateWithLifecycle()
    ManageRouteEffects(vm, state.frame.gate, nav)
    // Deleted: the management screens go, then the item's own page beneath (it would only wait for an item that is gone).
    LaunchedEffect(state.deleted) {
        if (state.deleted) {
            nav.leaveManage()
            nav.leaveItemPage(itemId)
        }
    }
    val actions = remember(vm, nav) {
        ManageActions(
            onBack = { nav.popBackStack() },
            onReload = vm::reload,
            onIdentify = { nav.navigate(ManageRoutes.identify(itemId)) { launchSingleTop = true } },
            onImages = { nav.navigate(ManageRoutes.images(itemId)) { launchSingleTop = true } },
            onEdit = { nav.navigate(ManageRoutes.edit(itemId)) { launchSingleTop = true } },
            onAskRefresh = vm::askRefresh, onSetRefresh = vm::setRefresh, onConfirmRefresh = vm::confirmRefresh, onDismissRefresh = vm::dismissRefresh,
            onAskDelete = vm::askDelete, onKeepCopy = vm::setKeepCopy, onConfirmDelete = vm::confirmDelete, onDismissDelete = vm::dismissDelete,
            onDismissProblem = vm::dismissProblem, onDismissNotice = vm::dismissNotice,
        )
    }
    ManageScreen(state, actions)
}

@Composable
private fun IdentifyRoute(graph: AppGraph, nav: NavHostController, itemId: String) {
    val vm = graphViewModel(key = "identify-$itemId") {
        IdentifyViewModel(itemId, graph.serverCatalog, graph.adminRights, graph.addressSelector, graph.manage, graph.library, afterChange(graph))
    }
    val state by vm.state.collectAsStateWithLifecycle()
    ManageRouteEffects(vm, state.frame.gate, nav)
    val actions = remember(vm, nav) {
        IdentifyActions(
            onBack = { nav.popBackStack() }, onSearch = vm::search, onUse = vm::askApply, onReplaceImages = vm::setReplaceImages,
            onConfirm = vm::confirmApply, onDismiss = vm::dismissApply, onDismissProblem = vm::dismissProblem, onDismissNotice = vm::dismissNotice,
        )
    }
    IdentifyScreen(state, vm.name, vm.year, actions)
}

@Composable
private fun ImagesRoute(graph: AppGraph, nav: NavHostController, itemId: String) {
    val vm = graphViewModel(key = "images-$itemId") {
        ImagesViewModel(itemId, graph.serverCatalog, graph.adminRights, graph.addressSelector, graph.manage, graph.library, graph.pickedImages::prepare, afterChange(graph))
    }
    val state by vm.state.collectAsStateWithLifecycle()
    ManageRouteEffects(vm, state.frame.gate, nav)
    // The system photo picker: the app gets one picture it may read, and needs no storage permission (S9, roadmap D3).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.upload(uri) }
    val actions = remember(vm, nav, picker) {
        ImagesActions(
            onBack = { nav.popBackStack() }, onRefresh = vm::refresh, onKind = vm::chooseKind,
            onUpload = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onFind = vm::findOnline, onAllLanguages = vm::setAllLanguages, onUse = vm::use, onRemove = vm::askRemove,
            onConfirmRemove = vm::confirmRemove, onDismissRemove = vm::dismissRemove,
            onDismissProblem = vm::dismissProblem, onDismissNotice = vm::dismissNotice,
        )
    }
    ImagesScreen(state, actions)
}

@Composable
private fun EditRoute(graph: AppGraph, nav: NavHostController, itemId: String) {
    val vm = graphViewModel(key = "edit-$itemId") {
        EditViewModel(itemId, graph.serverCatalog, graph.adminRights, graph.addressSelector, graph.manage, afterChange(graph))
    }
    val state by vm.state.collectAsStateWithLifecycle()
    ManageRouteEffects(vm, state.frame.gate, nav)
    val fields = remember(vm) {
        EditFields(vm.name, vm.sortName, vm.originalTitle, vm.overview, vm.year, vm.premiere, vm.officialRating, vm.communityRating, vm.inputs)
    }
    val actions = remember(vm, nav) {
        EditActions(
            onBack = { nav.popBackStack() }, onRefresh = vm::refresh, onLock = vm::setLocked, onLockAll = vm::setLockAll, onSave = vm::save,
            onAddEntry = vm::addEntry, onRemoveEntry = vm::removeEntry,
            onDismissProblem = vm::dismissProblem, onDismissNotice = vm::dismissNotice,
        )
    }
    EditScreen(state, fields, actions)
}
