package app.shoalwatch.ui.nav

import android.content.pm.PackageManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.shoalwatch.AppGraph
import app.shoalwatch.data.playback.MediaPaths
import app.shoalwatch.data.playback.PlaybackState
import app.shoalwatch.data.playback.StartFrom
import app.shoalwatch.ui.components.LocalCovered
import app.shoalwatch.ui.player.HostLifecycle
import app.shoalwatch.ui.player.LocalPipHost
import app.shoalwatch.ui.player.PipEffect
import app.shoalwatch.ui.player.PlayerActions
import app.shoalwatch.ui.player.PlayerScreen
import app.shoalwatch.ui.player.PlayerViewModel
import app.shoalwatch.ui.player.PlayerWindow
import app.shoalwatch.ui.player.TouchExploration
import app.shoalwatch.ui.player.VideoSurface
import app.shoalwatch.ui.player.rememberDolbyVisionScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The player. Its ViewModel (and so the controller and the engine) lives as long as this back-stack entry, so a rotation does not
 * rebuffer. It leaves as soon as the session ends or the controller closes: popped back to where Play was pressed (and if the
 * session changed, `ClearBrowsingOnSessionChange` takes the stack to Home as for any other screen). Popping by route does
 * nothing if that has already taken the player away.
 */
@Composable
internal fun PlayerRoute(graph: AppGraph, nav: NavHostController, itemId: String, from: StartFrom, queueToken: String? = null, sourceId: String? = null) {
    val vm = graphStateViewModel(key = "$itemId|$from|$queueToken|$sourceId") { saved ->
        PlayerViewModel(itemId, from, saved, graph.serverCatalog, { s, scope -> graph.playbackController(s, scope, graph.lockParts.profilePlayback.autoplayReader(s, scope)) }, graph.subtitleSettings, graph.qualityDefault, graph.addressSelector::refreshIfOffline, graph.housekeeping,
            { s -> graph.live.playQueues.get(s, queueToken) }, sourceId?.takeIf(MediaPaths::isSourceId), graph.live.remoteSubtitles,
        )
    }
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.leave) { if (state.leave) nav.popBackStack(Routes.PLAYER, inclusive = true) }
    val playing = state.playback.phase == PlaybackState.Phase.PLAYING || state.playback.phase == PlaybackState.Phase.BUFFERING
    PlayerWindow(keepOn = playing, locked = state.locked)
    HostLifecycle(onStopped = vm::onHostStopped, onStarted = vm::onHostStarted)
    TouchExploration(vm::setTouchExploration)
    // Picture-in-picture (Milestone 7): wished for while this player shows and nothing covers it; the small window's button plays and pauses.
    val pip = LocalPipHost.current?.takeIf { LocalContext.current.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) }
    val inPip by (pip?.inPip ?: NotInPip).collectAsStateWithLifecycle()
    val aspect by (vm.video?.aspectRatio ?: NoAspect).collectAsStateWithLifecycle()
    PipEffect(state, enabled = !LocalCovered.current, onPlayPause = vm::playPause, aspect = aspect)
    val actions = remember(vm, nav, pip) {
        PlayerActions(
            onBack = { nav.popBackStack(Routes.PLAYER, inclusive = true) },
            onTapSurface = vm::tapSurface,
            onPlayPause = vm::playPause,
            onSeek = vm::seekTo,
            onSkipBack = { vm.skipBy(-SKIP_MS) },
            onSkipForward = { vm.skipBy(SKIP_MS) },
            onDoubleTap = { forward -> vm.skipBy(if (forward) SKIP_MS else -SKIP_MS) },
            onPrevious = vm::previous,
            onNext = vm::next,
            onSkipSegment = vm::skipSegment,
            onPlayUpNext = vm::playUpNext,
            onCancelUpNext = vm::cancelUpNext,
            onToggleLock = vm::toggleLock,
            onOpenSheet = vm::openSheet,
            onCloseSheet = vm::closeSheet,
            onSelectAudio = vm::selectAudio,
            onSelectSubtitle = vm::selectSubtitle,
            onBurnIn = vm::burnIn,
            onQuality = vm::setQuality,
            onSpeed = vm::setSpeed,
            onSleep = vm::setSleep,
            onAppearance = vm::setAppearance,
            onDelay = vm::setDelay,
            onRetry = vm::retry,
            onSignIn = { vm.state.value.session?.let { nav.navigate(Routes.signIn(it.serverId)) } },
            onDismissSubtitleNotice = vm::dismissSubtitleNotice,
            onDismissSleepFired = vm::dismissSleepFired,
            onWatchedDelete = vm::watchedDelete,
            onWatchedTogether = vm::watchedTogether,
            onKeepDownload = vm::keepDownload,
            onDismissWatchedMessage = vm::dismissWatchedMessage,
            onSubmitSharerPin = vm::submitSharerPin,
            onCancelSharerPin = vm::cancelSharerPin,
            onEnterPip = pip?.let { host -> { host.enterNow(); Unit } },
            search = vm.searchActions,
        )
    }
    PlayerScreen(state, actions, rememberDolbyVisionScreen(), inPip, sharerPin = vm.sharerPin) { controlsUp ->
        VideoSurface(vm.video, vm.cues, state.appearance, controlsUp)
    }
}

private const val SKIP_MS = 10_000L
private val NotInPip: StateFlow<Boolean> = MutableStateFlow(false)
private val NoAspect: StateFlow<Float?> = MutableStateFlow(null)
