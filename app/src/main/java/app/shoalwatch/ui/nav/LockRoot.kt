package app.shoalwatch.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavGraph
import androidx.navigation.NavHostController
import app.shoalwatch.AppGraph
import app.shoalwatch.R
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.Session
import app.shoalwatch.security.AuthOutcome
import app.shoalwatch.security.DeviceAuth
import app.shoalwatch.security.Judged
import app.shoalwatch.security.LockGate
import app.shoalwatch.security.LockState
import app.shoalwatch.ui.components.LocalCovered
import app.shoalwatch.ui.findActivity
import app.shoalwatch.ui.lock.LockActions
import app.shoalwatch.ui.lock.LockScreen
import app.shoalwatch.ui.lock.LockViewModel
import app.shoalwatch.ui.lock.takesEveryTouch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Locked or not, as the screens must treat it. */
internal val LockState.locked: Boolean get() = this is LockState.App || this is LockState.Profile

/**
 * Covers the app until the gate has judged the active profile (review I3): [covered] is true unless the gate is open for exactly
 * the active session as the screens see it ([active]; null before the first value). While covered, [host] has no semantics
 * (TalkBack announces nothing of it), takes no touch, and is drawn under an opaque [cover] (the lock screen, or a plain blank
 * screen for the moment before the gate has judged a change). [LocalCovered] tells the host's dialogs, sheets and menus (separate
 * windows the cover cannot hide) to compose nothing while covered (review M1).
 */
@Composable
internal fun LockCover(openFor: Judged?, active: Judged?, host: @Composable () -> Unit, cover: @Composable () -> Unit) {
    val covered = openFor == null || active == null || openFor != active
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().then(if (covered) Modifier.clearAndSetSemantics {} else Modifier)) {
            CompositionLocalProvider(LocalCovered provides covered) { host() }
        }
        if (covered) cover()
    }
}

/**
 * The gate's state collected on `Dispatchers.Main.immediate` (review M2): a value the gate sets on the main thread (`onReturned` in
 * `onStart`) is written to the snapshot before that call returns, so the first frame after a return already has the cover.
 */
@Composable
internal fun <T> StateFlow<T>.collectAtOnce(): State<T> = collectAsState(context = Dispatchers.Main.immediate)

/** The active session as screens see it, wrapped so "not known yet" (null) differs from "nobody" (`Judged(null)`). */
@Composable
internal fun rememberActive(catalog: ServerCatalog): Judged? {
    val flow = remember(catalog) { catalog.activeAccount.map { a -> Judged(a?.let { Session(it.serverId, it.userId) }) } }
    return flow.collectAsState(initial = null).value
}

/**
 * While locked, the stack is one empty `locked` route (every screen, dialog, sheet and player of the locked profile is popped, so
 * their ViewModels are cleared and the player sends its stop); once open again, Home (Welcome with no server), fresh. Anything that
 * navigates while locked (a session change, a notification's tab) is undone at once: the effect watches the stack.
 */
// No public API gives the whole back stack as a flow (currentBackStackEntryFlow is the top only, visibleEntries the visible ones).
@android.annotation.SuppressLint("RestrictedApi")
@Composable
internal fun ResetOnLock(nav: NavHostController, state: LockState, hasServers: suspend () -> Boolean) {
    val locked = state.locked
    LaunchedEffect(nav, locked, state == LockState.Open) {
        withContext(Dispatchers.Main.immediate) {
            if (locked) {
                nav.currentBackStack.collect { stack ->
                    val entries = stack.filter { it.destination !is NavGraph }
                    if (entries.size != 1 || entries.single().destination.route != Routes.LOCKED) {
                        listOf(Routes.HOME, Routes.SEARCH, Routes.DOWNLOADS).forEach { nav.clearBackStack(it) }
                        nav.navigate(Routes.LOCKED) { popUpTo(nav.graph.id) { inclusive = true } }
                    }
                }
            } else if (state == LockState.Open && nav.currentBackStackEntry?.destination?.route == Routes.LOCKED) {
                val target = if (hasServers()) Routes.home() else Routes.WELCOME
                nav.navigate(target) { popUpTo(nav.graph.id) { inclusive = true } }
            }
        }
    }
}

/**
 * The lock screen's route: its ViewModel (scoped to the activity, outside navigation) and the phone's screen-lock prompt. The
 * prompt opens by itself once each time the app lock shows while the activity is resumed, and again on "Unlock"; only its
 * SUCCESS calls `LockGate.appUnlocked` (the one caller).
 */
@Composable
internal fun LockRoute(graph: AppGraph, lock: LockState) {
    val parts = graph.lockParts
    val vm = graphViewModel {
        LockViewModel(parts.lockGate, parts.switcher, graph.serverCatalog, graph.authenticator, parts.profileLocks.protectedProfiles, parts.forgotThrottle, parts.clearProtection)
    }
    val state by vm.state.collectAsState()
    var unavailable by remember { mutableStateOf(false) }
    val prompt = rememberUnlockPrompt(parts.lockGate, parts.deviceAuth) { unavailable = it }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    if (lock == LockState.App) {
        LaunchedEffect(lock) {
            lifecycle.currentStateFlow.first { it.isAtLeast(Lifecycle.State.RESUMED) }
            prompt()
        }
    }
    if (!lock.locked) {
        BlankCover()
        return
    }
    val actions = remember(vm) {
        LockActions(
            onUnlockApp = prompt, onSubmitPin = vm::submitPin, onUseProfile = vm::useProfile, onSubmitChosen = vm::submitChosenPin,
            onDismissChoice = vm::dismissChoice, onOpenForgot = vm::openForgot, onCloseForgot = vm::closeForgot, onSubmitForgot = vm::submitForgot,
        )
    }
    LockScreen(state.copy(lock = lock), vm.pin, vm.choicePin, vm.password, actions, unavailable)
}

@Composable
private fun rememberUnlockPrompt(gate: LockGate, auth: DeviceAuth, onUnavailable: (Boolean) -> Unit): () -> Unit {
    val activity = LocalContext.current.findActivity()
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.lock_prompt_title)
    val subtitle = stringResource(R.string.lock_prompt_subtitle)
    var running by remember { mutableStateOf(false) }
    return remember(activity, title, subtitle) {
        {
            if (activity != null && !running) {
                running = true
                scope.launch {
                    try {
                        gate.expectReturn()
                        when (auth.authenticate(activity, title, subtitle)) {
                            AuthOutcome.SUCCESS -> { onUnavailable(false); gate.appUnlocked() }
                            AuthOutcome.UNAVAILABLE -> onUnavailable(true)
                            AuthOutcome.CANCELLED, AuthOutcome.FAILED -> Unit
                        }
                    } finally {
                        gate.tripDone() // the prompt answered: its excuse ends here (final review M1)
                        running = false
                    }
                }
            }
        }
    }
}

/** Opaque, touch-taking and silent but for "Loading": the moment before the gate has judged a change. */
@Composable
internal fun BlankCover() {
    val label = stringResource(R.string.splash_loading)
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).takesEveryTouch().semantics { contentDescription = label })
}
