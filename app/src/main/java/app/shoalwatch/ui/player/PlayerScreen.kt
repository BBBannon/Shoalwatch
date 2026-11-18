package app.shoalwatch.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.core.AppError
import app.shoalwatch.data.playback.PlaybackState.Phase
import app.shoalwatch.data.playback.SubtitleChoice
import app.shoalwatch.data.playback.SubtitleNotice
import app.shoalwatch.ui.lock.PinDialog
import app.shoalwatch.ui.present.PlayerText
import app.shoalwatch.ui.present.rememberStrings

/**
 * The player: the picture ([video], given the route's `VideoSurface`; empty in tests and previews), the touch layer, the
 * controls, Up next or the skip button, an error with Try again, the sheets, and the notices (subtitles that could not be read
 * or drawn, the sleep timer pausing) as snackbars. [dolbyVisionScreen]: for the stream-info line only. [inPip]: in the
 * picture-in-picture window only the picture and its subtitles (no controls, gestures, cards, sheets or snackbars; a notice waits
 * for the full screen); back from it, the controls show once.
 */
@Composable
fun PlayerScreen(
    state: PlayerUiState,
    actions: PlayerActions,
    dolbyVisionScreen: Boolean = false,
    inPip: Boolean = false,
    /** The PIN field for "Watched together"'s protected sharer (security review M8 F12). */
    sharerPin: TextFieldState? = null,
    video: @Composable (controlsUp: Boolean) -> Unit = {},
) {
    val strings = rememberStrings()
    val p = state.playback
    val streamLine = PlayerText.streamInfo(strings, p.method, p.transcodeReasons, p.fellBackToTranscode, p.savedQuality)
    val hdrLine = PlayerText.hdrLine(strings, p.videoRange, p.method, p.transcodeReasons, dolbyVisionScreen)
    val snackbar = remember { SnackbarHostState() }
    // Locked, the system Back (an edge swipe with the bars hidden) does not leave: it shows the lock and its hint instead.
    BackHandler(enabled = state.locked) { actions.onTapSurface() }
    ControlsAfterPip(inPip, state.controlsVisible, actions.onTapSurface)
    if (!inPip) Notices(state, actions, snackbar)
    val ask = state.sharerPin
    if (ask != null && sharerPin != null && !inPip) {
        PinDialog(
            title = stringResource(R.string.together_pin_title, ask.name), body = stringResource(R.string.together_pin_body, ask.name),
            pin = sharerPin, answer = ask.answer, confirmLabel = stringResource(R.string.together_pin_confirm),
            onConfirm = actions.onSubmitSharerPin, onDismiss = actions.onCancelSharerPin, busy = ask.busy, cancelWhileBusy = false,
        )
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Always the first child, so the picture's view is kept as the window changes size.
        video(state.controlsVisible && !inPip)
        if (inPip) return@Box
        if (p.phase == Phase.FAILED) {
            Failed(p.error ?: AppError.Kind.PLAYBACK_FAILED, actions)
        } else {
            PlayerGestures(state, actions)
            if (p.phase == Phase.RESOLVING || p.phase == Phase.BUFFERING) Loading()
            if (state.controlsVisible) PlayerControls(state, actions, streamLine)
            if (!state.locked) Corner(state, actions)
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
    }
    if (!state.locked && !inPip) PlayerSheetHost(state, actions, streamLine, hdrLine)
}

/** Back from the picture-in-picture window to the full screen: the controls show once (then hide as usual). */
@Composable
private fun ControlsAfterPip(inPip: Boolean, controlsVisible: Boolean, show: () -> Unit) {
    val visible by rememberUpdatedState(controlsVisible)
    var wasInPip by remember { mutableStateOf(false) }
    LaunchedEffect(inPip) {
        if (wasInPip && !inPip && !visible) show()
        wasInPip = inPip
    }
}

@Composable
private fun BoxScope.Loading() {
    val label = stringResource(R.string.player_loading)
    CircularProgressIndicator(Modifier.align(Alignment.Center).semantics { contentDescription = label }, color = OnVideo)
}

/**
 * Up next, or else the skip button, at the bottom right (above the seek bar while the controls show), with the watch-and-delete
 * card stacked above it; on a short screen (landscape phone) the two cards sit side by side. Each card is limited to the space
 * there is and scrolls inside, so neither leaves the screen or covers the other. The corner is one traversal group read before
 * the controls (`PlayerCornerOrderTest`).
 */
@Composable
private fun BoxScope.Corner(state: PlayerUiState, actions: PlayerActions) {
    val p = state.playback
    val upNext = p.upNext?.takeIf { !it.cancelled }
    val segment = p.segmentNow
    val watched = state.watched
    val area = Modifier.matchParentSize().padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = if (state.controlsVisible) 112.dp else 24.dp)
    // Read first by TalkBack (Milestone 7 Task 8): the prompt, then Up next, then the controls, though the top bar is above them.
    BoxWithConstraints(area.semantics { isTraversalGroup = true; traversalIndex = -1f }) {
        val room = maxHeight
        val fit = Modifier.heightIn(max = room)
        if (watched != null && upNext != null && room < SIDE_BY_SIDE_BELOW) {
            Row(Modifier.align(Alignment.BottomEnd), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                WatchedPrompt(watched, actions, Modifier.weight(1f, fill = false).then(fit))
                UpNextCard(upNext, actions, Modifier.weight(1f, fill = false).then(fit))
            }
            return@BoxWithConstraints
        }
        Column(Modifier.align(Alignment.BottomEnd), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.End) {
            val below = upNext != null || segment != null
            watched?.let { WatchedPrompt(it, actions, Modifier.heightIn(max = if (below) room / 2 - 4.dp else room)) }
            val rest = Modifier.heightIn(max = if (watched != null) room / 2 - 4.dp else room)
            when {
                upNext != null -> UpNextCard(upNext, actions, rest)
                segment != null -> SkipSegmentButton(segment, actions.onSkipSegment)
            }
        }
    }
}

private val SIDE_BY_SIDE_BELOW = 480.dp

/** The error in words with what can be done: sign in again, or try again; and Back. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BoxScope.Failed(kind: AppError.Kind, actions: PlayerActions) {
    val signIn = kind == AppError.Kind.UNAUTHORISED || kind == AppError.Kind.NOT_SIGNED_IN
    Column(
        Modifier.align(Alignment.Center).widthIn(max = 560.dp).verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(AppError.of(kind).userMessage, style = MaterialTheme.typography.titleMedium, color = OnVideo)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = if (signIn) actions.onSignIn else actions.onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(if (signIn) R.string.action_sign_in else R.string.action_retry))
            }
            OutlinedButton(onClick = actions.onBack, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.cd_back), color = OnVideo) }
        }
    }
}

/** Subtitle notices and the sleep timer pausing: each said once in the snackbar, then dismissed. */
@Composable
private fun Notices(state: PlayerUiState, actions: PlayerActions, snackbar: SnackbarHostState) {
    val strings = rememberStrings()
    val notice = state.playback.subtitleNotice
    val subtitle = (state.playback.subtitle as? SubtitleChoice.On)?.index
    LaunchedEffect(notice) {
        when (notice) {
            null -> return@LaunchedEffect
            SubtitleNotice.UNREADABLE -> snackbar.showSnackbar(strings.get(R.string.subtitle_unreadable))
            SubtitleNotice.EFFECTS_NOT_DRAWN -> {
                val result = snackbar.showSnackbar(strings.get(R.string.subtitle_effects_not_drawn), strings.get(R.string.subtitle_let_server_draw), withDismissAction = true)
                if (result == SnackbarResult.ActionPerformed && subtitle != null) actions.onBurnIn(subtitle)
            }
        }
        actions.onDismissSubtitleNotice()
    }
    val watched = state.watchedMessage
    LaunchedEffect(watched) {
        if (watched == null) return@LaunchedEffect
        snackbar.showSnackbar(strings.get(watchedMessageText(watched)))
        actions.onDismissWatchedMessage()
    }
    LaunchedEffect(state.sleepFired) {
        if (!state.sleepFired) return@LaunchedEffect
        snackbar.showSnackbar(strings.get(R.string.sleep_fired))
        actions.onDismissSleepFired()
    }
}
