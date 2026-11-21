package app.shoalwatch.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.shoalwatch.AppGraph
import app.shoalwatch.ui.browse.BulkHost
import app.shoalwatch.ui.browse.BulkViewModel

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
    return remember(vm, nav, state, canDownload) {
        BulkHost(
            state, vm.notices, vm::apply, vm::setKeepCopy, vm::confirmDelete, vm::dismissDelete,
            onManage = { nav.navigate(app.shoalwatch.ui.manage.ManageRoutes.hub(it)) { launchSingleTop = true } },
            canDownload = canDownload,
        )
    }
}
