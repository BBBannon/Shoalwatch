package app.shoalwatch.ui.nav

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.shoalwatch.AppGraph
import app.shoalwatch.data.playback.StartFrom
import app.shoalwatch.ui.discover.TapOnce
import app.shoalwatch.ui.findActivity
import app.shoalwatch.ui.present.Destination
import kotlinx.coroutines.launch

/* The discovery routes (Milestone 11): the detail pages' extras block, a person, the filtered list, genres and studios. */

/** Opens a destination once until the page is back on top, so a double tap stacks one page, not two. */
internal fun NavHostController.openOnce(tap: TapOnce): (Destination) -> Unit = { d -> tap.once { open(d) } }

/**
 * Clear filters on the filtered list. With no filter the cached grid is what shows, so it goes back to the library's own page;
 * the all-libraries list (a genre from Search: no [parentId]) goes back to Search rather than list the whole server (T8 review).
 * A library's list with no library page beneath it clears in place.
 */
internal fun NavHostController.clearBrowseFilters(parentId: String?, clearInPlace: () -> Unit) {
    if (parentId == null) popBackStack()
    else if (!popBackStack(Routes.LIBRARY, inclusive = false)) clearInPlace()
}

/** The extras block's ViewModel and what the page passes its screen. */
internal class ExtrasHost(val vm: app.shoalwatch.ui.discover.ExtrasViewModel, val controls: app.shoalwatch.ui.discover.ExtrasControls)

/**
 * The extras block of the detail and series pages (Milestone 11): its ViewModel, read again where the page comes back on top, and its
 * actions wired to navigation. A local trailer plays in the app's player; an online one is handed to Android as a plain `ACTION_VIEW`
 * (the app never fetches it), and a second tap before the page is back on top does nothing.
 */
@Composable
internal fun rememberExtras(graph: AppGraph, nav: NavHostController, itemId: String, snackbar: SnackbarHostState): ExtrasHost {
    val vm = graphViewModel(key = "extras:$itemId") { app.shoalwatch.ui.discover.ExtrasViewModel(itemId, graph.serverCatalog, graph.live.discover, graph.live.lists) }
    val state by vm.state.collectAsStateWithLifecycle()
    androidx.lifecycle.compose.LifecycleResumeEffect(vm) {
        vm.onResumed()
        onPauseOrDispose {}
    }
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val noApp = androidx.compose.ui.res.stringResource(app.shoalwatch.R.string.extras_no_app)
    val actions = remember(vm, nav, context, noApp) {
        app.shoalwatch.ui.discover.ExtrasActions(
            onOpen = { d -> vm.once { nav.open(d) } },
            onOpenPerson = { id -> vm.once { nav.navigate(Routes.person(id)) } },
            onPlayTrailer = { id -> vm.once { nav.navigate(Routes.player(id, StartFrom.BEGINNING)) } },
            onWatchOnline = { trailer ->
                val intent = app.shoalwatch.data.discover.trailerIntent(trailer.url)
                val activity = context.findActivity()
                if (intent == null || activity == null) {
                    scope.launch { snackbar.showSnackbar(noApp) }
                } else vm.once {
                    try {
                        activity.startActivity(intent)
                    } catch (e: android.content.ActivityNotFoundException) {
                        vm.release()
                        scope.launch { snackbar.showSnackbar(noApp) }
                    }
                }
            },
            onChooseVersion = vm::chooseVersion,
            onRetry = vm::retry,
        )
    }
    return ExtrasHost(vm, app.shoalwatch.ui.discover.ExtrasControls(state, actions))
}

/** A person's page (Milestone 11): their picture and overview and their films and shows. */
@Composable
internal fun PersonRoute(graph: AppGraph, nav: NavHostController, personId: String) {
    val vm = graphViewModel(key = personId) { app.shoalwatch.ui.discover.PersonViewModel(personId, graph.serverCatalog, graph.live.discover) }
    val state by vm.state.collectAsStateWithLifecycle()
    val tap = app.shoalwatch.ui.discover.rememberTapOnce()
    val actions = remember(vm, nav, tap) {
        app.shoalwatch.ui.discover.PersonActions(
            onBack = { nav.popBackStack() }, onOpen = nav.openOnce(tap), onEndVisible = vm::onEndVisible, onRetry = vm::retry, onRetryMore = vm::retryMore,
            onOpenServers = { nav.navigate(Routes.SERVERS) },
        )
    }
    app.shoalwatch.ui.discover.PersonScreen(state, actions)
}

/** A library's live filtered list (Milestone 11), opened with no filter or at one genre or studio. */
@Composable
internal fun BrowseRoute(graph: AppGraph, nav: NavHostController, parentId: String?, genreId: String?, studioId: String?) {
    val vm = graphViewModel(key = "$parentId|$genreId|$studioId") {
        app.shoalwatch.ui.discover.BrowseViewModel(
            parentId, app.shoalwatch.data.discover.BrowseFilter(genreId = genreId, studioId = studioId), graph.serverCatalog, graph.library, graph.live.discover,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val tap = app.shoalwatch.ui.discover.rememberTapOnce()
    val actions = remember(vm, nav, tap) {
        app.shoalwatch.ui.discover.BrowseActions(
            onBack = { nav.popBackStack() }, onOpen = nav.openOnce(tap), onGenre = vm::setGenre, onStudio = vm::setStudio, onYear = vm::setYear,
            onRating = vm::setRating, onTag = vm::setTag, onLetter = vm::setLetter,
            onClear = { nav.clearBrowseFilters(parentId, vm::clearFilters) }, onEndVisible = vm::onEndVisible,
            onRetry = vm::retry, onRetryMore = vm::retryMore, onOpenServers = { nav.navigate(Routes.SERVERS) },
        )
    }
    app.shoalwatch.ui.discover.BrowseScreen(state, actions)
}

/** A library's genres or studios (Milestone 11); each opens the filtered list. */
@Composable
internal fun NamesRoute(graph: AppGraph, nav: NavHostController, parentId: String, kind: app.shoalwatch.ui.discover.NamesKind) {
    val vm = graphViewModel(key = "$kind|$parentId") { app.shoalwatch.ui.discover.NamesViewModel(parentId, kind, graph.serverCatalog, graph.live.discover) }
    val state by vm.state.collectAsStateWithLifecycle()
    val tap = app.shoalwatch.ui.discover.rememberTapOnce()
    val actions = remember(vm, nav, tap) {
        app.shoalwatch.ui.discover.NamesActions(
            onBack = { nav.popBackStack() },
            onOpen = { named ->
                tap.once { nav.navigate(if (kind == app.shoalwatch.ui.discover.NamesKind.GENRES) Routes.browse(parentId, genreId = named.id) else Routes.browse(parentId, studioId = named.id)) }
            },
            onEndVisible = vm::onEndVisible, onRetry = vm::retry, onOpenServers = { nav.navigate(Routes.SERVERS) },
        )
    }
    app.shoalwatch.ui.discover.NamesScreen(state, actions)
}
