package app.shoalwatch.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import app.shoalwatch.ui.servers.ServersPinDialogs
import app.shoalwatch.ui.servers.ServersScreen
import app.shoalwatch.ui.servers.ServersViewModel
import app.shoalwatch.R
import app.shoalwatch.security.AuthOutcome
import app.shoalwatch.ui.findActivity
import app.shoalwatch.ui.lock.Door
import app.shoalwatch.ui.lock.GrownUpDialog
import app.shoalwatch.ui.settings.SettingsActions
import app.shoalwatch.ui.settings.SettingsScreen
import app.shoalwatch.ui.settings.SettingsViewModel
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.LifecycleResumeEffect
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
internal fun SignInRoute(graph: AppGraph, nav: NavHostController, serverId: String, userName: String? = null, userId: String? = null) {
    val vm = graphViewModel(key = "$serverId/${userId.orEmpty()}") {
        SignInViewModel(serverId, graph.authenticator, graph.serverCatalog, graph.addressSelector, expectedUserId = userId)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    // "Sign in again" for an expired sign-in fills in that profile's name once (not again after a rotation the user typed over).
    var prefilled by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(userName) { if (userName != null && !prefilled) { prefilled = true; vm.onUsernameChange(userName) } }
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
    val vm = graphViewModel {
        ServersViewModel(
            graph.serverCatalog, graph.authenticator, sharing = graph.downloads::sharing, rights = graph.adminRights, sync = graph.sync,
            switcher = graph.lockParts.switcher, household = graph.lockParts.householdGuard, protection = graph.lockParts.profileLocks.protectedProfiles,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val prompt by vm.switchPrompt.collectAsStateWithLifecycle()
    val grownUp by vm.grownUpAsk.collectAsStateWithLifecycle()
    LaunchedEffect(state.openSettings) {
        state.openSettings?.let { id ->
            vm.settingsOpened()
            nav.navigate(Routes.serverEdit(id))
        }
    }
    val actions = remember(vm) {
        ServersActions(
            onBack = { nav.popBackStack() },
            onAddServer = { nav.navigate(Routes.ADD_SERVER) },
            onSwitch = vm::switchTo,
            onAskSignOut = vm::askSignOut,
            onAskRemove = vm::askRemoveServer,
            onAddAccount = { nav.navigate(Routes.signIn(it)) },
            onSignInAgain = { serverId, userId, userName -> nav.navigate(Routes.signInAgain(serverId, userId, userName)) },
            onSettings = vm::openSettings,
            onConfirm = vm::confirm,
            onDismissConfirm = vm::dismissConfirm,
            onDismissNotice = vm::dismissNotice,
            onAdmin = { nav.navigate(AdminRoutes.DASHBOARD) { launchSingleTop = true } },
            onSubmitSwitch = vm::submitSwitch,
            onDismissSwitch = vm::dismissSwitch,
            onChooseGrownUp = vm::chooseGrownUp,
            onSubmitGrownUp = vm::submitGrownUp,
            onDismissGrownUp = vm::dismissGrownUp,
            onOpenAppSettings = { nav.navigate(Routes.SETTINGS) },
        )
    }
    ServersScreen(state, actions, ServersPinDialogs(prompt, vm.switchPin, grownUp, vm.grownUpPin))
}

@Composable
internal fun ServerEditRoute(graph: AppGraph, nav: NavHostController, serverId: String) {
    val vm = graphViewModel(key = serverId) {
        ServerEditViewModel(
            serverId, graph.serverCatalog, graph.authenticator, graph.certificateProber, graph.addressSelector,
            household = graph.lockParts.householdGuard,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val door by vm.door.state.collectAsStateWithLifecycle()
    LaunchedEffect(door) { if (door == Door.Shut) nav.popBackStack() }
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
    vm.door.grownUp?.let { flow ->
        val ask by flow.ask.collectAsStateWithLifecycle()
        ask?.let { GrownUpDialog(it, flow.pin, flow::choose, flow::submit, vm.door::dismiss) }
    }
}

/**
 * Settings. Turning the app lock on or off asks the phone's own screen lock first (`expectReturn`, so the prompt is not
 * "leaving"); only its yes goes on to the ViewModel, which then asks a grown-up when one is needed. Not an account route: a
 * session change pops it.
 */
@Composable
internal fun SettingsRoute(graph: AppGraph, nav: NavHostController) {
    val parts = graph.lockParts
    val vm = graphViewModel {
        SettingsViewModel(
            graph.serverCatalog, parts.profilePlayback, parts.profileLocks, parts.lockPrefs, parts.householdGuard, parts.deviceAuth::available,
            parts.lockGate.hideRecents, parts.pipPrefs,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val grownUp by vm.grownUp.ask.collectAsStateWithLifecycle()
    LifecycleResumeEffect(vm) {
        vm.refreshDevice()
        onPauseOrDispose {}
    }
    val activity = LocalContext.current.findActivity()
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.lock_prompt_title)
    val subtitle = stringResource(R.string.lock_prompt_subtitle)
    val actions = remember(vm, nav, activity) {
        SettingsActions(
            onBack = { nav.popBackStack() },
            onAutoplay = vm::setAutoplay,
            onPipAuto = vm::setPipAuto,
            onProtect = vm::startProtect,
            onChangePin = vm::startChangePin,
            onRemovePin = vm::startRemovePin,
            onQuickConnect = { nav.navigate(Routes.QUICK_CONNECT) { launchSingleTop = true } },
            onSubmitPin = vm::submitPin,
            onCancelPin = vm::cancelPin,
            onAppLock = { on ->
                if (activity != null) {
                    scope.launch {
                        parts.lockGate.expectReturn()
                        val outcome = try {
                            parts.deviceAuth.authenticate(activity, title, subtitle)
                        } finally {
                            parts.lockGate.tripDone() // the prompt answered: its excuse ends here (final review M1)
                        }
                        if (outcome == AuthOutcome.SUCCESS) vm.setAppLock(on)
                    }
                }
            },
            onLockAfter = vm::setLockAfter,
            onHideInRecents = vm::setHideInRecents,
            onDownloadSettings = { nav.navigate(Routes.DOWNLOAD_SETTINGS) },
            onChooseGrownUp = vm.grownUp::choose,
            onSubmitGrownUp = vm.grownUp::submit,
            onDismissGrownUp = vm.grownUp::dismiss,
        )
    }
    SettingsScreen(state, actions, vm.pin, grownUp, vm.grownUp.pin)
}

/** Approve a Quick Connect code from another device (Milestone 10). Not an account route: a session change pops it. */
@Composable
internal fun QuickConnectRoute(graph: AppGraph, nav: NavHostController) {
    val vm = graphViewModel {
        val locks = graph.lockParts.profileLocks
        app.shoalwatch.ui.settings.QuickConnectApproveViewModel(graph.serverCatalog, graph.quickConnectApprover::approve, locks::isProtected, locks::check)
    }
    val state by vm.state.collectAsStateWithLifecycle()
    val actions = remember(vm, nav) {
        app.shoalwatch.ui.settings.QuickConnectActions(
            onBack = { nav.popBackStack() }, onApprove = vm::approve, onOpenServers = { nav.navigate(Routes.SERVERS) },
            onSubmitPin = vm::submitPin, onCancelPin = vm::cancelPin,
        )
    }
    app.shoalwatch.ui.settings.QuickConnectApproveScreen(state, vm.code, actions, vm.pin)
}
