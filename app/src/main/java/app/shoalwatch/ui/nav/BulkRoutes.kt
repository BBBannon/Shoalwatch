package app.shoalwatch.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.shoalwatch.AppGraph
import app.shoalwatch.ui.browse.BulkHost
import app.shoalwatch.ui.browse.BulkViewModel
import app.shoalwatch.ui.lists.ListPickerHost
import app.shoalwatch.ui.lists.ListPickerViewModel

/**
 * Multi-select for a browsing screen (Milestone 10): one [BulkViewModel] per screen entry, keyed apart from the screen's own.
 * [canDownload] is false on Downloads (it is already there). A delete also clears the item's pictures from memory.
 */
@Composable
internal fun rememberBulkHost(graph: AppGraph, nav: NavHostController, canDownload: Boolean = true): BulkHost {
    val vm = graphViewModel(key = "bulk") {
        BulkViewModel(
            graph.serverCatalog, graph.playstate, graph.downloads, graph.manage, graph.adminRights, graph.library,
            afterDelete = { s, id -> graph.artworkEviction.evictItem(s.serverId, id) },
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm, nav) { vm.deleted.collect { nav.leaveItemPage(it) } }
    val picker = rememberListPicker(graph)
    return remember(vm, nav, state, canDownload, picker) {
        BulkHost(
            state, vm.notices, vm::apply, vm::setKeepCopy, vm::confirmDelete, vm::dismissDelete,
            onManage = { nav.navigate(app.shoalwatch.ui.manage.ManageRoutes.hub(it)) { launchSingleTop = true } },
            canDownload = canDownload,
            picker = picker,
        )
    }
}

/**
 * After [itemId] was deleted from the server, leaves its own page (film, episode or series) if that page is the one on top;
 * anywhere else (a library, Home, search) this does nothing, as the item has already left those lists.
 */
internal fun NavHostController.leaveItemPage(itemId: String) {
    val args = currentBackStackEntry?.arguments ?: return
    if (args.getString(Routes.ARG_ITEM) == itemId || args.getString(Routes.ARG_SERIES) == itemId) popBackStack()
}

/** The add-to picker (Milestone 11): one [ListPickerViewModel] per screen entry, keyed apart from the screen's own. */
@Composable
internal fun rememberListPicker(graph: AppGraph): ListPickerHost {
    val vm = graphViewModel(key = "picker") { ListPickerViewModel(graph.serverCatalog, graph.lists) }
    val state by vm.state.collectAsStateWithLifecycle()
    return remember(vm, state) {
        ListPickerHost(state, vm.notices, vm.name, vm::open, vm::choose, vm::startCreate, vm::backToList, vm::create, vm::retry, vm::dismiss)
    }
}
