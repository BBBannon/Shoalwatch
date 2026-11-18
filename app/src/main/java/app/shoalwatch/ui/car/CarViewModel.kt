package app.shoalwatch.ui.car

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.core.AppError
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.downloads.Downloads
import app.shoalwatch.data.downloads.OfflineEntry
import app.shoalwatch.data.library.Freshness
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.Library
import app.shoalwatch.data.playback.PlaybackController
import app.shoalwatch.data.playback.PlaybackState
import app.shoalwatch.data.playback.PlaybackState.Phase
import app.shoalwatch.data.playback.Quality
import app.shoalwatch.data.playback.StartFrom
import app.shoalwatch.data.playback.engine.SubtitleFrame
import app.shoalwatch.data.playback.engine.VideoOutput
import app.shoalwatch.data.servers.Session
import app.shoalwatch.security.Judged
import app.shoalwatch.security.LockState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Who the car screen may show (see "Android Auto (parked)" in docs/ARCHITECTURE.md). Never printed. */
sealed interface CarAccess {
    /** Start-up, or a change of profile the lock has not judged yet: nothing of any profile. */
    data object Waiting : CarAccess
    /** Nobody is signed in on the phone. */
    data object SignIn : CarAccess
    /** The app lock or a protected profile's PIN is asked on the phone. */
    data object Locked : CarAccess
    /** The car activity is on the phone's own display (started there, or moved there when Android Auto went): nothing of any profile. */
    data object PhoneDisplay : CarAccess
    data class Open(val session: Session) : CarAccess { override fun toString() = "Open(<redacted>)" }
}

/** The car's lists for the open profile [session] (what they were read for). [offline]: the server cannot be reached, so only [downloads] play. */
data class CarLists(
    val session: Session? = null,
    val resume: List<ItemWithUserData> = emptyList(),
    val nextUp: List<ItemWithUserData> = emptyList(),
    val downloads: List<ItemWithUserData> = emptyList(),
    val offline: Boolean = false,
) { override fun toString() = "CarLists(<redacted>)" }

/** The car's full-screen player. Never printed (it holds a title). */
data class CarPlayer(val playback: PlaybackState, val controlsVisible: Boolean = true) {
    override fun toString() = "CarPlayer(<redacted>)"
}

/**
 * The car screen: the profile active on the phone, and only while the lock is open for exactly that profile (the phone's own
 * `LockCover` rule). Its player is an ordinary [PlaybackController] registered with `ActivePlayback`, so the phone and the car never
 * both play: starting on one ends the other. Leaving the open state (a lock, another profile, a sign-out) ends the car's player at
 * once. The car pauses when it loses the screen ([onHostPaused]) and never plays again by itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CarViewModel(
    activeSession: Flow<Session?>,
    lockState: StateFlow<LockState>,
    openFor: StateFlow<Judged?>,
    private val library: Library,
    private val downloads: Downloads,
    private val controllerFactory: (Session, CoroutineScope) -> PlaybackController,
    private val quality: () -> Quality,
) : ViewModel() {
    /** True until the activity says it is on a car display: fails closed (review I1). */
    private val onPhoneDisplay = MutableStateFlow(true)

    /** On the phone's own display at once, without waiting for the profile store's first answer; back on a car, nothing until it answers. */
    val access: StateFlow<CarAccess> = onPhoneDisplay.flatMapLatest { phone ->
        if (phone) {
            flowOf<CarAccess>(CarAccess.PhoneDisplay)
        } else {
            flow<CarAccess> { emit(CarAccess.Waiting); emitAll(combine(activeSession, lockState, openFor) { s, state, judged -> accessOf(s, state, judged) }) }
        }
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, CarAccess.Waiting)

    val lists: StateFlow<CarLists?> = access.flatMapLatest { a ->
        // Nothing first: the old profile's lists never stay up under a new one while its own loads (review I2).
        if (a is CarAccess.Open) flow<CarLists?> { emit(null); emitAll(listsFor(a.session)) } else flowOf(null)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val mutablePlayer = MutableStateFlow<CarPlayer?>(null)
    val player: StateFlow<CarPlayer?> = mutablePlayer.asStateFlow()

    private var controller: PlaybackController? = null
    private var watching: Job? = null
    private var hideJob: Job? = null
    private var hostPaused = false

    /** The engine's picture, for `VideoSurface` only; null while nothing plays. */
    internal val video: VideoOutput? get() = controller?.video

    /** What subtitles to draw now, for `VideoSurface` only. */
    internal val cues: StateFlow<SubtitleFrame>? get() = controller?.cues

    init {
        // A lock, another profile or a sign-out: the car's player stops at once (its stop goes as its own profile).
        viewModelScope.launch {
            access.collect { a -> if (controller != null && (a as? CarAccess.Open)?.session != mutablePlayer.value?.playback?.session) stop() }
        }
    }

    /** Plays [itemId] for the open profile (a download plays from the phone, with or without a connection). */
    fun play(itemId: String) {
        val s = (access.value as? CarAccess.Open)?.session ?: return
        if (lists.value?.session != s) return // a row of another profile's lists, still on screen for a moment
        stop()
        val c = controllerFactory(s, viewModelScope)
        controller = c
        mutablePlayer.value = CarPlayer(PlaybackState.EMPTY.copy(session = s))
        watching = viewModelScope.launch { c.state.collect { p -> if (p.closed) closed() else onPlayback(p) } }
        if (hostPaused) c.onHostStopped()
        c.setQuality(quality())
        c.open(itemId, StartFrom.RESUME)
    }

    fun playPause() = touched { it.playPause() }
    fun skipBy(ms: Long) = touched { it.skipBy(ms) }

    /** A tap on the picture shows or hides the controls; it never pauses by itself. */
    fun tapPicture() {
        val p = mutablePlayer.value ?: return
        if (p.controlsVisible) { hideJob?.cancel(); mutablePlayer.value = p.copy(controlsVisible = false) } else showControls()
    }

    /** Whether the activity is on the phone's own display (true until the activity says otherwise: fails closed). */
    fun setOnPhoneDisplay(on: Boolean) { onPhoneDisplay.value = on }

    /** Back: stops (the stop is sent) and returns to the list. */
    fun back() = stop()

    /** Android Auto took the screen (the car moved off, another app): pause, and stay paused. */
    fun onHostPaused() { hostPaused = true; controller?.onHostStopped() }

    fun onHostResumed() { hostPaused = false; controller?.onHostStarted(); showControls() }

    override fun onCleared() = stop()

    private fun stop() {
        val c = controller ?: return
        controller = null
        watching?.cancel()
        hideJob?.cancel()
        mutablePlayer.value = null
        c.end(null)
    }

    /** Ended from outside (the phone started playing, the profile changed): back to the list. */
    private fun closed() {
        controller = null
        hideJob?.cancel()
        mutablePlayer.value = null
        watching?.cancel()
    }

    private fun onPlayback(p: PlaybackState) {
        val before = mutablePlayer.value ?: return
        mutablePlayer.value = before.copy(playback = p)
        if (p.phase != before.playback.phase) {
            if (p.phase == Phase.PLAYING) scheduleHide() else if (p.phase != Phase.BUFFERING) showControls()
        }
    }

    private inline fun touched(block: (PlaybackController) -> Unit) {
        controller?.let(block) ?: return
        showControls()
    }

    private fun showControls() {
        mutablePlayer.update { it?.copy(controlsVisible = true) }
        scheduleHide()
    }

    private fun scheduleHide() {
        hideJob?.cancel()
        if (mutablePlayer.value?.playback?.phase != Phase.PLAYING) return
        hideJob = viewModelScope.launch { delay(SHOWN_MS); mutablePlayer.update { it?.copy(controlsVisible = false) } }
    }

    private fun listsFor(s: Session): Flow<CarLists> =
        combine(library.home(s), downloadedFor(s)) { home, saved ->
            val offline = (home.resume.freshness as? Freshness.Stale)?.reason.let { it == AppError.Kind.OFFLINE || it == AppError.Kind.NETWORK }
            CarLists(s, home.resume.items.take(ROW_LIMIT), home.nextUp.items.take(ROW_LIMIT), saved, offline)
        }

    /** Every complete download this profile may play now (its storage is in the phone): films first, then each series' episodes. */
    private fun downloadedFor(s: Session): Flow<List<ItemWithUserData>> = downloads.library(s).flatMapLatest { lib ->
        val films = lib.films.playable()
        val series = lib.series.take(SERIES_LIMIT).map { summary -> downloads.series(s, summary.series.item.id) }
        if (series.isEmpty()) {
            flowOf(films)
        } else {
            combine(series) { all -> films + all.flatMap { one -> one.seasons.flatMap { it.episodes.playable() } } }
        }
    }.map { it.take(DOWNLOAD_LIMIT) }

    private fun List<OfflineEntry>.playable() = filter { it.download.state == DownloadState.COMPLETE && it.download.available }.map { it.item }

    companion object {
        /** Mirrors `LockCover`: nothing of a profile until the gate is open for exactly the active one. */
        fun accessOf(active: Session?, state: LockState, judged: Judged?, onPhoneDisplay: Boolean = false): CarAccess = when {
            onPhoneDisplay -> CarAccess.PhoneDisplay
            state == LockState.Checking -> CarAccess.Waiting
            state is LockState.App || state is LockState.Profile -> CarAccess.Locked
            judged == null || judged != Judged(active) -> CarAccess.Waiting
            active == null -> CarAccess.SignIn
            else -> CarAccess.Open(active)
        }

        const val ROW_LIMIT = 12
        const val DOWNLOAD_LIMIT = 40
        private const val SERIES_LIMIT = 20
        private const val SHOWN_MS = 5_000L
        const val BACK_MS = 10_000L
        const val FORWARD_MS = 30_000L
    }
}
