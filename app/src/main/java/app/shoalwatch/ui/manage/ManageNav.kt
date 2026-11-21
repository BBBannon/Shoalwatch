package app.shoalwatch.ui.manage

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.navigation.NavGraph
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import app.shoalwatch.ui.admin.AdminGate
import app.shoalwatch.ui.admin.AdminScreenModel

/**
 * The library management routes (Milestone 10). Each carries the item's id (as the item route does; it is validated again by the
 * gateway) and nothing else. Like the admin routes they are not account routes, so any profile change pops them; a closed gate
 * (lost administrator, sign-out, 401) pops every one of them at once ([ManageRouteEffects]).
 */
internal object ManageRoutes {
    const val ARG_ITEM = "itemId"
    const val HUB = "manage/{$ARG_ITEM}"
    const val IDENTIFY = "manage/{$ARG_ITEM}/identify"
    const val IMAGES = "manage/{$ARG_ITEM}/images"
    const val EDIT = "manage/{$ARG_ITEM}/edit"
    val ALL = setOf(HUB, IDENTIFY, IMAGES, EDIT)

    fun hub(itemId: String) = "manage/${Uri.encode(itemId)}"
    fun identify(itemId: String) = "manage/${Uri.encode(itemId)}/identify"
    fun images(itemId: String) = "manage/${Uri.encode(itemId)}/images"
    fun edit(itemId: String) = "manage/${Uri.encode(itemId)}/edit"
}

/** The routes with their screens passed in, so a test can drive this exact graph. */
internal fun NavGraphBuilder.manageDestinations(
    hub: @Composable (itemId: String) -> Unit,
    identify: @Composable (itemId: String) -> Unit,
    images: @Composable (itemId: String) -> Unit,
    edit: @Composable (itemId: String) -> Unit,
) {
    val arg = listOf(navArgument(ManageRoutes.ARG_ITEM) { type = NavType.StringType })
    composable(ManageRoutes.HUB, arg) { hub(it.arguments?.getString(ManageRoutes.ARG_ITEM).orEmpty()) }
    composable(ManageRoutes.IDENTIFY, arg) { identify(it.arguments?.getString(ManageRoutes.ARG_ITEM).orEmpty()) }
    composable(ManageRoutes.IMAGES, arg) { images(it.arguments?.getString(ManageRoutes.ARG_ITEM).orEmpty()) }
    composable(ManageRoutes.EDIT, arg) { edit(it.arguments?.getString(ManageRoutes.ARG_ITEM).orEmpty()) }
}

/** Pops every management screen at once; does nothing unless one is on top (so it can never pop the item or Home). */
internal fun NavHostController.leaveManage() {
    @android.annotation.SuppressLint("RestrictedApi") // no public API gives the whole back stack; currentBackStackEntry is the top
    fun top() = currentBackStack.value.lastOrNull { it.destination !is NavGraph }?.destination?.route
    while (top() in ManageRoutes.ALL) {
        if (!popBackStack()) return
    }
}

/** Loads only while started (visible), and leaves every management screen the moment the gate closes. */
@Composable
internal fun ManageRouteEffects(vm: AdminScreenModel, gate: AdminGate, nav: NavHostController) {
    LifecycleStartEffect(vm) {
        vm.setVisible(true)
        onStopOrDispose { vm.setVisible(false) }
    }
    LaunchedEffect(gate) { if (gate is AdminGate.Closed) nav.leaveManage() }
}
