package app.shoalwatch.ui.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.data.playback.EndReason
import app.shoalwatch.data.playback.PlaybackController
import app.shoalwatch.data.playback.PlaybackState
import app.shoalwatch.data.playback.PlaybackState.Phase
import app.shoalwatch.data.playback.Quality
import app.shoalwatch.data.playback.QualityDefault
import app.shoalwatch.data.playback.SleepChoice
import app.shoalwatch.data.playback.SleepState
import app.shoalwatch.data.playback.StartFrom
import app.shoalwatch.data.playback.SubtitleAppearance
import app.shoalwatch.data.playback.SubtitleSettings
import app.shoalwatch.data.playback.engine.SubtitleFrame
import app.shoalwatch.data.playback.engine.VideoOutput
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The player screen's state, around one [PlaybackController] that lives as long as this ViewModel (so a rotation does not
 * rebuffer). See "Player screen" in docs/ARCHITECTURE.md.
 *
 * - **Owner:** the first active session seen owns playback. Any later different value (another profile, or nobody) ends the
 *   controller at once ([EndReason.SESSION_CHANGED] only while the old account is still signed in, otherwise
 *   [EndReason.SIGNED_OUT], as `ActivePlayback` decides) and sets [PlayerUiState.leave]. A closed controller leaves too.
 * - **Saved state** holds only the owner's ids, the playing item's id and its position, never a title. Restored for a different
 *   session: nothing plays; for the same one, that item at that position.
 * - **Quality** starts at [QualityDefault.start]; a choice in the Quality sheet becomes the saved default.
 * - **Controls** hide 4 s after the last touch while playing, never with touch exploration on, a sheet open, or paused.
 */
class PlayerViewModel(
    private val itemId: String,
    private val from: StartFrom,
    private val saved: SavedStateHandle,
    private val catalog: ServerCatalog,
    private val controllerFactory: (Session, CoroutineScope) -> PlaybackController,
    private val subtitleSettings: SubtitleSettings,
    private val quality: QualityDefault,
    /** Asks the address selector to probe this server again now if it is `Offline` (`ServerConnections.refreshIfOffline`, as every screen's user refresh does): no token, no URL. */
    private val reprobe: (serverId: String) -> Unit,
) : ViewModel() {
    private val _state = MutableStateFlow(PlayerUiState(appearance = subtitleSettings.appearance.value))
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private var controller: PlaybackController? = null
    private var decided = false
    private var hideJob: Job? = null
    private var infoJob: Job? = null
    private var lastSleep: SleepState = SleepState.Off
    private var pendingSeekMs: Long? = null
    /** Restored after the process was ended: the episode that was playing (it may not be the route's), and so where to seek. */
    private var restoredItem: String? = null

    /** The engine's video output, for `VideoSurface` only; null before playback starts or once it has ended. */
    internal val video: VideoOutput? get() = controller?.takeUnless { _state.value.leave }?.video

    /** What subtitles to draw now, for `VideoSurface` only. */
    internal val cues: StateFlow<SubtitleFrame>? get() = controller?.cues

    init {
        subtitleSettings.refresh() // follows a change in the phone's caption settings made since the last time
        viewModelScope.launch { subtitleSettings.appearance.collect { a -> _state.update { it.copy(appearance = a) } } }
        viewModelScope.launch { catalog.activeSession().collect(::onSession) }
    }

    private suspend fun onSession(s: Session?) {
        val owner = _state.value.session
        if (owner == null) {
            if (decided) return
            decided = true
            val restored = restoredOwner()
            if (s == null || (restored != null && restored != s)) return leave()
            start(s)
        } else if (s != owner) {
            val c = controller
            // Ended now as nobody (nothing sent), unless the old account is still signed in: then its stop goes as itself.
            c?.end(if (s != null && stillSignedIn(owner)) EndReason.SESSION_CHANGED else EndReason.SIGNED_OUT)
            leave()
        }
    }

    private suspend fun stillSignedIn(s: Session) = catalog.accounts(s.serverId).first().any { it.userId == s.userId }

    private fun restoredOwner(): Session? {
        val server = saved.get<String>(KEY_SERVER) ?: return null
        val user = saved.get<String>(KEY_USER) ?: return null
        restoredItem = saved.get<String>(KEY_ITEM)
        pendingSeekMs = saved.get<Long>(KEY_POSITION)?.takeIf { it > 0 && restoredItem != null }
        return Session(server, user)
    }

    private fun start(s: Session) {
        saved[KEY_SERVER] = s.serverId
        saved[KEY_USER] = s.userId
        val c = controllerFactory(s, viewModelScope)
        controller = c
        _state.update { it.copy(session = s) }
        viewModelScope.launch { c.state.collect(::onPlayback) }
        c.setQuality(quality.start()) // the saved default, or 8 Mbps on mobile data (final review I-2)
        // Restored: the episode that was playing (after Up next it is not the route's), back at its own position below.
        restoredItem?.let { c.open(it, StartFrom.RESUME) } ?: c.open(itemId, from)
    }

    private fun leave() {
        hideJob?.cancel()
        _state.update { it.copy(playback = PlaybackState.CLOSED, leave = true, sheet = null) }
    }

    private fun onPlayback(p: PlaybackState) {
        if (_state.value.leave) return
        if (p.closed) return leave()
        val before = _state.value.playback
        val fired = lastSleep != SleepState.Off && p.sleep == SleepState.Off
        lastSleep = p.sleep
        _state.update { it.copy(playback = p, sleepFired = it.sleepFired || fired) }
        // The position saved is always the saved item's own (Task 8 review I-3): a new item starts with none.
        if (p.itemId != null && p.itemId != saved.get<String>(KEY_ITEM)) {
            saved[KEY_ITEM] = p.itemId
            saved.remove<Long>(KEY_POSITION)
        }
        val ready = p.phase == Phase.BUFFERING || p.phase == Phase.PLAYING || p.phase == Phase.PAUSED
        if (ready && p.method != null) {
            if (p.itemId == restoredItem) pendingSeekMs?.let { pendingSeekMs = null; controller?.seekTo(it) }
            if (p.positionMs > 0) saved[KEY_POSITION] = p.positionMs
        }
        if (before.method == null && p.method != null) showStreamInfo()
        if (p.phase != before.phase) {
            if (p.phase == Phase.PLAYING) scheduleHide() else if (p.phase != Phase.BUFFERING) showControls()
        }
    }

    private fun showStreamInfo() {
        infoJob?.cancel()
        _state.update { it.copy(streamInfoShown = true) }
        infoJob = viewModelScope.launch { delay(SHOWN_MS); _state.update { it.copy(streamInfoShown = false) } }
    }

    private fun showControls() {
        _state.update { it.copy(controlsVisible = true) }
        scheduleHide()
    }

    /** From the last touch; only while playing, with no sheet open and touch exploration off. */
    private fun scheduleHide() {
        hideJob?.cancel()
        val s = _state.value
        if (s.playback.phase != Phase.PLAYING || s.touchExploration || s.sheet != null) return
        hideJob = viewModelScope.launch { delay(SHOWN_MS); _state.update { it.copy(controlsVisible = false) } }
    }

    /** Every control: does [block] and keeps the controls up for another 4 s. */
    private inline fun touched(block: (PlaybackController) -> Unit) {
        controller?.let(block)
        showControls()
    }

    fun tapSurface() {
        if (_state.value.controlsVisible && !_state.value.locked) {
            hideJob?.cancel()
            _state.update { it.copy(controlsVisible = false) }
        } else {
            showControls()
        }
    }

    fun setTouchExploration(on: Boolean) {
        _state.update { it.copy(touchExploration = on) }
        showControls()
    }

    fun openSheet(sheet: PlayerSheet) { _state.update { it.copy(sheet = sheet, controlsVisible = true) }; scheduleHide() }
    fun closeSheet() { _state.update { it.copy(sheet = null) }; showControls() }
    fun toggleLock() { _state.update { it.copy(locked = !it.locked, sheet = null) }; showControls() }

    fun playPause() = touched { it.playPause() }
    fun seekTo(ms: Long) = touched { it.seekTo(ms) }
    fun skipBy(ms: Long) = touched { it.skipBy(ms) }
    fun previous() = touched { it.previous() }
    fun next() = touched { it.next() }
    fun skipSegment() = touched { it.skipSegment() }
    fun playUpNext() = touched { it.playUpNextNow() }
    fun cancelUpNext() = touched { it.cancelUpNext() }
    fun selectAudio(index: Int) = touched { it.selectAudio(index) }
    fun selectSubtitle(index: Int?) = touched { it.selectSubtitle(index) }
    fun burnIn(index: Int) = touched { it.burnInSubtitle(index) }
    /** The user's choice is also the saved default for every later film (a device setting). */
    fun setQuality(q: Quality) = touched { quality.choose(q); it.setQuality(q) }
    fun setSpeed(speed: Float) = touched { it.setSpeed(speed) }
    fun setDelay(ms: Int) = touched { it.setSubtitleDelay(ms) }
    fun dismissSubtitleNotice() { controller?.dismissSubtitleNotice() }
    fun dismissSleepFired() = _state.update { it.copy(sleepFired = false) }
    fun setAppearance(a: SubtitleAppearance) = subtitleSettings.update(a)

    /**
     * The user's own choice (turning it off included) is never "paused by the sleep timer". [lastSleep] is set BEFORE the
     * controller is told: with `Main.immediate` its new state reaches [onPlayback] inside that call (Task 8 review I-2).
     */
    fun setSleep(choice: SleepChoice) = touched { c ->
        if (choice == SleepChoice.OFF) lastSleep = SleepState.Off
        c.setSleep(choice)
        lastSleep = c.state.value.sleep
    }

    /**
     * Asks for a fresh probe of the server first (the user's own action never waits for the selector's backoff), then opens the
     * item again, as from the detail screen's Resume (the stop has saved the position): the resolve waits for that probe.
     */
    fun retry() = touched { c ->
        _state.value.session?.let { reprobe(it.serverId) }
        c.open(c.state.value.itemId ?: itemId, StartFrom.RESUME)
    }

    /** The activity stopped: pause and report (no background playback in this milestone). */
    fun onHostStopped() { controller?.onHostStopped() }

    /** Back in front: the controls show (playback stays paused until the user plays). */
    fun onHostStarted() = showControls()

    override fun onCleared() {
        controller?.end(null)
        controller = null
    }

    companion object {
        const val KEY_SERVER = "player_server"
        const val KEY_USER = "player_user"
        const val KEY_POSITION = "player_position"
        /** The episode playing now (an id, never a title): after Up next, Next or Previous it is not the route's. */
        const val KEY_ITEM = "player_item"
        private const val SHOWN_MS = 4_000L
    }
}
