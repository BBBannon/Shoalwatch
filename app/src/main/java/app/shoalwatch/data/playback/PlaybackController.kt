package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.core.MonotonicClock
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.Library
import app.shoalwatch.data.playback.PlaybackState.Phase
import app.shoalwatch.data.playback.api.PlaybackGateway
import app.shoalwatch.data.playback.engine.EngineEvent
import app.shoalwatch.data.playback.engine.EngineFailure
import app.shoalwatch.data.playback.engine.PlayerEngine
import app.shoalwatch.data.playback.engine.SubtitleFrame
import app.shoalwatch.data.playback.engine.VideoOutput
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class StartFrom { RESUME, BEGINNING }

/**
 * The player's state machine, one per player screen, bound to one [session]. Main thread only ([scope] is the screen's main
 * scope; [appScope] carries the reports, which outlive the screen). See "Player controller" in docs/ARCHITECTURE.md.
 *
 * It owns the [PlayerEngine], resolves through [resolver] (falling back to a transcode once, re-resolving once after a network
 * or sign-in failure, asking for a new route when one goes stale), reports through a [ProgressReporter] per play session, and
 * publishes [state], which never carries a URL, route, token or Media3 type, and [cues] (what subtitles to draw now, opaque).
 * Tracks and subtitles are [TrackControl]'s (see "Tracks and subtitles"). [end] is the only way out: it releases the engine
 * (and so the media session) at once and publishes [PlaybackState.CLOSED]; [ActivePlayback] calls it when the session changes
 * or the account goes. Positions are believed only while the engine is really playing, or when the app or a seek set them, so a
 * 0 from an engine that is preparing, seeking or has lost its output is never reported.
 */
class PlaybackController internal constructor(
    private val session: Session,
    private val engine: PlayerEngine,
    private val resolver: PlaybackResolver,
    private val reports: PlaybackReports,
    private val gateway: PlaybackGateway,
    private val library: Library,
    private val navigator: EpisodeNavigator,
    private val activePlayback: ActivePlayback,
    scope: CoroutineScope,
    private val appScope: CoroutineScope,
    private val clock: MonotonicClock,
    private val autoplayNext: () -> Boolean,
    tracks: TrackPreferences,
    subtitles: SubtitleLoader,
    captions: () -> CaptionDefaults,
) {
    private var queue: PlayQueue? = null // in force until the screen ends (see [PlayQueue]); only [open] sets it
    private val _state = MutableStateFlow(PlaybackState.EMPTY)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /** Every coroutine of this controller; cancelled by [end]. */
    private val work = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private var token: ActivePlayback.Token? = null
    private var ended = false
    private var current: PlayingItem? = null
    private var loading: Job? = null
    private var phase = Phase.IDLE
    private var error: AppError.Kind? = null
    private var quality = Quality.MAXIMUM
    private var speed = 1f
    private var hostStopped = false // every load starts paused while it is set (see onHostStopped)
    private val sleep = SleepControl(clock)

    private val trackControl = TrackControl(session, engine, resolver, tracks, subtitles, captions, work, appScope, object : TrackControl.Host {
        override val current get() = this@PlaybackController.current
        override fun reload(c: PlayingItem, how: suspend () -> Resolved) = reload(c, Stop.NORMAL, how)
        override fun tracksChanged(c: PlayingItem) { c.reporter?.onTracksChanged(snapshot(c)); publish() }
        override fun publish() = this@PlaybackController.publish()
        override fun cuePositionMs(c: PlayingItem) = if (phase == Phase.PLAYING) engine.positionMs.takeIf { it > 0 } ?: c.knownMs else c.knownMs
        override val reloading get() = loading?.isActive == true
    })

    /** The subtitles to draw now (text from the app's own timeline, at the position minus the delay; pictures from the stream). */
    val cues: StateFlow<SubtitleFrame> = trackControl.cues

    /** Where the screen puts its `SurfaceView`: the engine's video output, nothing else of the engine (let go by [end]). */
    val video: VideoOutput get() = engine.video

    fun open(itemId: String, from: StartFrom, queue: PlayQueue? = null, mediaSourceId: String? = null) {
        if (ended) return
        if (token == null) {
            val t = activePlayback.begin(session) { end(it) }
            if (ended) return // refused: this session is not the active one
            token = t
            work.launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { onEvent(it) } }
            work.launch { while (true) { delay(TICK_MS); tick() } }
            work.launch { while (true) { delay(CUE_TICK_MS); if (phase == Phase.PLAYING) trackControl.refreshCues() } }
        }
        queue?.let { this.queue = it } // null keeps the queue (a retry)
        play(itemId, from, mediaSourceId) // the version is for this play only
    }

    fun playPause() {
        val c = current ?: return
        when (phase) {
            Phase.PLAYING -> pauseAndReport(c)
            // While buffering, by the user's intent (not the engine's state): Pause must pause a start or a rebuffer.
            Phase.BUFFERING -> if (engine.playWhenReady) engine.pause() else engine.play()
            Phase.PAUSED -> engine.play()
            else -> Unit
        }
    }

    fun seekTo(ms: Long) {
        val c = current ?: return
        if (c.resolved == null) return
        val target = ms.coerceIn(0, durationOf(c) ?: Long.MAX_VALUE)
        c.knownMs = target
        engine.seekTo(target)
        publish()
    }

    fun skipBy(deltaMs: Long) { current?.let { seekTo(it.knownMs + deltaMs) } }

    /** To the end of the segment the position is in (an Outro's end shows Up next, as the item ending would). */
    fun skipSegment() { current?.let { c -> c.segmentAt()?.let { seekTo(it.endMs) } } }

    fun next() { current?.around?.next?.let { play(it.id, StartFrom.RESUME) } }

    /** Within 5 s of the start: the previous episode; otherwise back to the start of this one. */
    fun previous() {
        val c = current ?: return
        val previous = c.around.previous
        if (c.knownMs <= RESTART_WINDOW_MS && previous != null) play(previous.id, StartFrom.RESUME) else seekTo(0)
    }

    fun playUpNextNow() { current?.upNext?.let { play(it.itemId, StartFrom.RESUME) } }

    fun cancelUpNext() { current?.upNext?.cancel(); publish() }

    /** 0.5, 0.75, 1.0, 1.25, 1.5, 1.75 or 2.0; anything else is ignored. */
    fun setSpeed(speed: Float) {
        if (speed !in PlaybackRules.SPEEDS) return
        this.speed = speed
        engine.setSpeed(speed)
        publish()
    }

    /** Asks again at the current position with the new quality (a new play session). */
    fun setQuality(quality: Quality) {
        this.quality = quality
        val c = current
        val plan = c?.resolved?.plan
        if (c == null || plan == null) return publish()
        val wanted = trackControl.want(c) { it.copy(quality = quality) }.also { if (phase == Phase.PAUSED) c.startPaused = true } // a reload while paused loads paused (never clears an earlier mark)
        reload(c, stop = Stop.NORMAL) { resolver.resolve(session, wanted, TrackRules.burnInFormat(plan.copy(request = wanted))) }
    }

    /** By identity; DIRECT_PLAY switches in the player, anything else asks again at the current position. Remembered for the series. */
    fun selectAudio(index: Int) = trackControl.selectAudio(index)

    /** Null: off. A text track is fetched and drawn by the app (no new stream); a picture track needs one except in DIRECT_PLAY. */
    fun selectSubtitle(index: Int?) = trackControl.selectSubtitle(index)

    /** "Let the server draw these subtitles" (offered when [PlaybackState.subtitleEffectsNotDrawn]). */
    fun burnInSubtitle(index: Int) = trackControl.burnInSubtitle(index)

    fun setSubtitleDelay(ms: Int) = trackControl.setDelay(ms)

    fun dismissSubtitleNotice() = trackControl.dismissNotice()

    fun setSleep(choice: SleepChoice) { sleep.set(choice); publish() }

    /** The screen went off or the app was left: pause, report, no Up next, no headset play (N-4), every load starts paused (I-3). */
    fun onHostStopped() {
        hostStopped = true
        engine.setHostStopped(true)
        val c = current ?: return
        if (phase == Phase.PLAYING) pauseAndReport(c) else engine.pause()
        c.upNext?.cancel()
        publish()
    }

    /** Back in front: a media-session controller may start playback again (the film itself stays paused). */
    fun onHostStarted() { hostStopped = false; engine.setHostStopped(false) }

    /**
     * Ends at once: engine and media session released, state emptied. [reason] null is a normal close (the stop is sent and the
     * record released); [EndReason.SESSION_CHANGED] sends the stop as this (the old) session; [EndReason.SIGNED_OUT] sends nothing.
     */
    fun end(reason: EndReason?) {
        if (ended) return
        ended = true
        work.cancel()
        val c = current
        current = null
        if (reason != EndReason.SIGNED_OUT && c != null) stopReport(c, failed = false)
        engine.setMediaMetadata(null, null)
        engine.release()
        token?.let(activePlayback::release) // a no-op when ActivePlayback ended it; a self-ended one must not stay held (M-3)
        token = null
        trackControl.clear()
        _state.value = PlaybackState.CLOSED
    }

    private fun play(itemId: String, from: StartFrom, mediaSourceId: String? = null) {
        // The old item is stopped and silent while the next resolves; a failed resolve leaves it paused (Task 6 review M-2).
        current?.let { stopReport(it, failed = false); if (it.resolved != null) engine.pause() }
        val c = PlayingItem(itemId)
        current = c
        phase = Phase.RESOLVING
        error = null
        publish()
        loading?.cancel()
        loading = work.launch {
            val entry = withTimeoutOrNull(ITEM_WAIT_MS) { library.item(session, itemId).first { it.item != null } }?.item
            if (entry == null) return@launch fail(c, AppError.Kind.SERVER_NOT_FOUND)
            c.entry = entry
            trackControl.recall(c)
            publish()
            if (queue != null || entry.item.kind == ItemKind.EPISODE) work.launch { c.around = navigator.around(session, entry.item, queue); if (current === c) publish() }
            load(c, PlaybackRules.startAt(entry.userData?.positionMs ?: 0, entry.item.runtimeMs, from), Stop.NONE) { resolver.resolve(session, PlaybackRequest(itemId, quality, mediaSourceId = mediaSourceId)) }
        }
    }

    private enum class Stop { NONE, NORMAL, FAILED }

    /** Stops the play session as [stop] says, then resolves with [how] and loads at the known position. */
    private fun reload(c: PlayingItem, stop: Stop, how: suspend () -> Resolved) {
        phase = Phase.BUFFERING
        loading?.cancel()
        loading = work.launch { load(c, c.knownMs, stop, how) }
    }

    private suspend fun load(c: PlayingItem, atMs: Long, stop: Stop, how: suspend () -> Resolved) {
        if (stop != Stop.NONE) stopReport(c, failed = stop == Stop.FAILED)
        val first = c.resolved == null
        val resolved = try {
            how().let { if (first) trackControl.initial(c, it) else it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return fail(c, AppError.from(e).kind)
        }
        if (ended || current !== c) return
        c.resolved = resolved
        c.knownMs = atMs
        if (c.reporter == null) c.reporter = ProgressReporter(reports, session, appScope, clock, atMs)
        phase = Phase.BUFFERING
        trackControl.prepare(c, resolved.plan)
        c.entry?.item?.let { engine.setMediaMetadata(it.name, it.seriesName) } // only now: never the next title over the old stream (m-7)
        engine.load(resolved.engineItem(), atMs, playWhenReady = !(c.startPaused.also { c.startPaused = false } || hostStopped))
        publish()
        if (first && resolved.plan.hasSegments) work.launch { gateway.segmentsOrNull(session, c.itemId)?.let { if (current === c) { c.segments = it; publish() } } }
    }

    private fun onEvent(event: EngineEvent) {
        val c = current ?: return
        if (c.resolved == null || phase == Phase.FAILED) return
        when (event) {
            EngineEvent.Ready -> {
                c.reroutes = 0
                if (phase == Phase.BUFFERING || phase == Phase.RESOLVING) phase = if (engine.isPlaying) Phase.PLAYING else Phase.PAUSED
                trackControl.onReady(c)
            }
            EngineEvent.Buffering -> phase = Phase.BUFFERING
            is EngineEvent.PlayingChanged -> when {
                event.playing -> { phase = Phase.PLAYING; c.reporter?.onPlaying(snapshot(c)) }
                phase == Phase.PLAYING -> pausedNow(c) // a pause from anywhere: the user, audio focus, a headset
            }
            EngineEvent.BecameNoisy -> if (phase == Phase.PLAYING) pausedNow(c)
            EngineEvent.Ended -> onEnded(c)
            is EngineEvent.Failed -> return onFailed(c, event.category)
            is EngineEvent.PositionDiscontinuity -> seeked(c, event.toMs)
            is EngineEvent.PictureCues -> c.pictureFrame = event.frame
        }
        publish()
    }

    private fun tick() {
        val c = current ?: return
        if (c.resolved == null || phase == Phase.FAILED || phase == Phase.RESOLVING) {
            if (phase == Phase.RESOLVING && sleep.takeDue()) { c.startPaused = true; publish() } // honoured: it loads paused (m-3)
            return
        }
        val before = c.knownMs
        val now = positionOf(c)
        val finishAt = finishedAt(c)
        if (phase == Phase.PLAYING && PlaybackRules.crossedByPlayback(before, now, finishAt)) c.finished = true
        c.outro()?.let { if (now >= it.startMs) c.offerUpNext(sleep.atEndOfItem, autoplayNext) }
        if (sleep.takeDue()) sleepNow(c)
        c.upNext?.let { countdown ->
            if (phase != Phase.PAUSED) countdown.advance(TICK_MS)
            if (countdown.due) return play(countdown.itemId, StartFrom.RESUME)
        }
        c.reporter?.tick(snapshot(c))
        publish()
    }

    private fun onEnded(c: PlayingItem) {
        if (c.knownMs < (finishedAt(c) ?: Long.MAX_VALUE)) c.finished = true // played to the end, not seeked past the mark
        durationOf(c)?.let { c.knownMs = it }
        phase = Phase.ENDED
        if (sleep.takeEndOfItem()) {
            c.upNextShown = true // the sleep wins: no Up next from the credits on the next tick either
            engine.pause()
            return
        }
        c.offerUpNext(sleep.atEndOfItem, autoplayNext)
    }

    private fun onFailed(c: PlayingItem, failure: EngineFailure) {
        val resolved = c.resolved ?: return
        val plan = resolved.plan
        positionOf(c)
        when (val r = PlaybackRules.recovery(failure, plan.method, c.fellBack, c.reResolved, c.reroutes)) {
            // Both from the latest wanted request, so a track change still being asked for is not undone (Task 7 review I-1).
            Recovery.FallBack -> { c.fellBack = true; val p = plan.copy(request = trackControl.want(c) { it.copy(forceTranscode = true) }); reload(c, Stop.FAILED) { resolver.fallBack(session, p) } }
            Recovery.ReResolve -> { c.reResolved = true; val p = plan.copy(request = trackControl.wanted(c)); reload(c, Stop.NORMAL) { resolver.resolve(session, p.request, TrackRules.burnInFormat(p)) } }
            // A track change still being asked for is asked for again, not replaced by the old plan on a new route (m-1).
            Recovery.Reroute -> { c.reroutes++; val w = trackControl.wanted(c); reload(c, Stop.NONE) { if (w == plan.request) resolver.reroute(resolved) else resolver.resolve(session, w, TrackRules.burnInFormat(plan.copy(request = w))) } }
            Recovery.SignedOut -> return end(EndReason.SIGNED_OUT)
            is Recovery.Fail -> fail(c, r.kind)
        }
        publish()
    }

    private fun fail(c: PlayingItem, kind: AppError.Kind) {
        if (ended || current !== c) return
        stopReport(c, failed = true)
        c.upNext = null
        phase = Phase.FAILED
        error = kind
        publish()
    }

    private fun seeked(c: PlayingItem, toMs: Long) {
        c.knownMs = toMs
        c.reporter?.onSeeked(snapshot(c))
        c.withdrawUpNextBefore(toMs, durationOf(c))
    }

    /** A due sleep timer (already off) always pauses, whatever the phase (buffering, ended), and cancels an Up next countdown. */
    private fun sleepNow(c: PlayingItem) {
        c.upNext?.cancel()
        if (loading?.isActive == true) c.startPaused = true // a reload in flight would otherwise start it playing again
        if (phase == Phase.PLAYING) pauseAndReport(c) else engine.pause()
    }

    private fun pauseAndReport(c: PlayingItem) {
        if (phase != Phase.PLAYING) return
        pausedNow(c)
        engine.pause()
    }

    /** The snapshot is taken while still PLAYING, so the engine's position is read (unless it says 0). */
    private fun pausedNow(c: PlayingItem) {
        val snapshot = snapshot(c).copy(paused = true)
        phase = Phase.PAUSED
        c.reporter?.onPaused(snapshot)
    }

    private fun stopReport(c: PlayingItem, failed: Boolean) {
        val reporter = c.reporter ?: return
        c.reporter = null
        reporter.stop(snapshot(c).copy(failed = failed), finished = c.finished && !failed)
    }

    /** The engine's position, believed only while it is really playing and not 0; otherwise the last believed one. */
    private fun positionOf(c: PlayingItem): Long {
        if (phase == Phase.PLAYING) engine.positionMs.takeIf { it > 0 }?.let { c.knownMs = it }
        return c.knownMs
    }

    private fun snapshot(c: PlayingItem): PlaybackReport = c.report(positionOf(c), paused = phase != Phase.PLAYING)

    private fun durationOf(c: PlayingItem): Long? = c.durationMs(engine.durationMs)

    private fun finishedAt(c: PlayingItem): Long? = PlaybackRules.finishedAt(c.outro()?.startMs, durationOf(c))

    private fun publish() {
        if (ended) return
        val c = current
        val buffered = if (c?.resolved != null) engine.bufferedMs else 0
        _state.value = stateOf(session, c, phase, c?.let(::durationOf), buffered, quality, speed, sleep.state, error)
        trackControl.refreshCues()
    }

    private companion object {
        const val TICK_MS = 250L
        /** How often the drawn subtitles follow the position while playing. */
        const val CUE_TICK_MS = 50L
        const val ITEM_WAIT_MS = 5_000L
        const val RESTART_WINDOW_MS = 5_000L
    }
}
