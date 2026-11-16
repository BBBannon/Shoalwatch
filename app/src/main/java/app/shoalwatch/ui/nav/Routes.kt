package app.shoalwatch.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.shoalwatch.AppGraph
import app.shoalwatch.ui.admin.AdminRoutes
import app.shoalwatch.ui.onboarding.AddServerActions
import app.shoalwatch.ui.onboarding.AddServerScreen
import app.shoalwatch.ui.onboarding.AddServerViewModel
import app.shoalwatch.ui.onboarding.SignInActions
import app.shoalwatch.ui.onboarding.SignInScreen
import app.shoalwatch.ui.onboarding.SignInViewModel
import app.shoalwatch.ui.rememberClientCertChooser
import app.shoalwatch.ui.servers.PaneActions
import app.shoalwatch.ui.servers.ServerEditActions
import app.shoalwatch.ui.servers.ServerEditScreen
import app.shoalwatch.ui.servers.ServerEditViewModel
import app.shoalwatch.ui.servers.ServersActions
import app.shoalwatch.ui.servers.ServersScreen
import app.shoalwatch.ui.servers.ServersViewModel
import kotlinx.coroutines.launch

/*
 * One small composable per route: it builds the ViewModel from the graph, collects its state and wires the
 * screen's lambdas to ViewModel functions and navigation. All logic is in the ViewModels.
 */

/** Where a successful sign-in goes: a fresh Home, alone on the stack. */
internal fun NavHostController.toHome() = navigate(Routes.home(fresh = true)) { popUpTo(graph.id) { inclusive = true } }

@Composable
internal fun AddServerRoute(graph: AppGraph, nav: NavHostController, firstRun: Boolean) {
    val vm = graphViewModel { AddServerViewModel(graph.authenticator, graph.certificateProber, graph.serverCatalog) }
    val state by vm.state.collectAsStateWithLifecycle()
    val chooser = rememberClientCertChooser()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.savedServerId) {
        state.savedServerId?.let { id ->
            // On the very first run Welcome stays underneath, so the back stack is never empty.
            nav.navigate(Routes.signIn(id)) { if (!firstRun) popUpTo(Routes.ADD_SERVER) { inclusive = true } }
        }
    }
    val actions = remember(vm, chooser) {
        AddServerActions(
            onAddressChange = vm::onAddressChange,
            onContinue = vm::continueClicked,
            onCancelCheck = vm::cancelCheck,
            onOfferHttp = vm::offerHttp,
            onAllowHttp = vm::allowHttp,
            onTrustCertificate = vm::trustCertificate,
            onChooseClientCertificate = { scope.launch { vm.chooseClientCertificate(chooser) } },
            onConfirmFound = vm::confirmFound,
            onSecondAddressChange = vm::onSecondAddressChange,
            onAddSecond = vm::addSecondAddress,
            onSkipSecond = vm::skipSecondAddress,
            onBackToAddress = vm::backToAddress,
        )
    }
    AddServerScreen(state, actions, onLeave = if (firstRun) null else ({ nav.popBackStack(); Unit }))
}

@Composable
internal fun SignInRoute(graph: AppGraph, nav: NavHostController, serverId: String) {
    val vm = graphViewModel(key = serverId) { SignInViewModel(serverId, graph.authenticator, graph.serverCatalog, graph.addressSelector) }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.signedIn) { if (state.signedIn) nav.toHome() }
    val actions = remember(vm) {
        SignInActions(
            onUsernameChange = vm::onUsernameChange,
            onSignIn = vm::signInWithPassword,
            onUsePassword = vm::usePassword,
            onUseQuickConnect = vm::useQuickConnect,
            onRetryQuickConnect = vm::retryQuickConnect,
            onBack = { nav.popBackStack() },
        )
    }
    SignInScreen(state, vm.password, actions)
}

@Composable
internal fun ServersRoute(graph: AppGraph, nav: NavHostController) {
    val vm = graphViewModel { ServersViewModel(graph.serverCatalog, graph.authenticator, sharing = graph.downloads::sharing, rights = graph.adminRights, sync = graph.sync) }
    val state by vm.state.collectAsStateWithLifecycle()
    val actions = remember(vm) {
        ServersActions(
            onBack = { nav.popBackStack() },
            onAddServer = { nav.navigate(Routes.ADD_SERVER) },
            onSwitch = vm::switchTo,
            onAskSignOut = vm::askSignOut,
            onAskRemove = vm::askRemoveServer,
            onAddAccount = { nav.navigate(Routes.signIn(it)) },
            onSettings = { nav.navigate(Routes.serverEdit(it)) },
            onConfirm = vm::confirm,
            onDismissConfirm = vm::dismissConfirm,
            onDismissNotice = vm::dismissNotice,
            onAdmin = { nav.navigate(AdminRoutes.DASHBOARD) { launchSingleTop = true } },
        )
    }
    ServersScreen(state, actions)
}

@Composable
internal fun ServerEditRoute(graph: AppGraph, nav: NavHostController, serverId: String) {
    val vm = graphViewModel(key = serverId) {
        ServerEditViewModel(serverId, graph.serverCatalog, graph.authenticator, graph.certificateProber, graph.addressSelector)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val chooser = rememberClientCertChooser()
    val scope = rememberCoroutineScope()
    LaunchedEffect(state.missing) { if (state.missing) nav.popBackStack() }
    val actions = remember(vm, chooser) {
        ServerEditActions(
            onBack = { nav.popBackStack() },
            onRetry = vm::retry,
            onEditAddress = vm::editAddress,
            onSetCleartext = vm::setCleartext,
            onForgetCertificate = vm::askForgetCertificate,
            onReviewCertificate = vm::reviewCertificate,
            onChooseClientCertificate = { scope.launch { vm.chooseClientCertificate(chooser) } },
            onClearClientCertificate = vm::askRemoveClientCertificate,
            onAddHeader = vm::addHeader,
            onEditHeader = vm::editHeader,
            onRemoveHeader = vm::askRemoveHeader,
            pane = PaneActions(
                onDismiss = vm::dismissPane,
                onConfirmCleartext = vm::confirmCleartext,
                onConfirmDestructive = vm::confirmDestructive,
                onTrustReviewed = vm::trustReviewedCertificate,
                onAddressText = vm::onAddressText,
                onSaveAddress = vm::saveAddress,
                onCancelAddressCheck = vm::cancelAddressCheck,
                onOfferHttp = vm::offerHttp,
                onAllowHttp = vm::allowHttpForAddress,
                onTrustAddressCertificate = vm::trustAddressCertificate,
                onChooseAddressClientCertificate = { scope.launch { vm.chooseClientCertificateForAddress(chooser) } },
                onHeaderName = vm::onHeaderName,
                onHeaderValue = vm::onHeaderValue,
                onSaveHeader = vm::saveHeader,
            ),
        )
    }
    ServerEditScreen(state, actions)
}
