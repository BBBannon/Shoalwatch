package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.core.MonotonicClock
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.Library
import app.shoalwatch.data.playback.PlaybackState.Phase
import app.shoalwatch.data.playback.api.PlaybackGateway
import app.shoalwatch.data.playback.engine.EngineEvent
import app.shoalwatch.data.playback.engine.EngineFailure
import app.shoalwatch.data.playback.engine.EngineItem
import app.shoalwatch.data.playback.engine.PlayerEngine
import app.shoalwatch.data.playback.engine.SubtitleFrame
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
 * (and so the media session) at once and publishes [PlaybackState.EMPTY]; [ActivePlayback] calls it when the session changes
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
    private var sleep: SleepState = SleepState.Off

    private val trackControl = TrackControl(session, engine, resolver, tracks, subtitles, captions, work, appScope, object : TrackControl.Host {
        override val current get() = this@PlaybackController.current
        override fun reload(c: PlayingItem, how: suspend () -> Resolved) = reload(c, Stop.NORMAL, how)
        override fun tracksChanged(c: PlayingItem) { c.reporter?.onTracksChanged(snapshot(c)); publish() }
        override fun publish() = this@PlaybackController.publish()
        override fun cuePositionMs(c: PlayingItem) = if (phase == Phase.PLAYING) engine.positionMs.takeIf { it > 0 } ?: c.knownMs else c.knownMs
    })

    /** The subtitles to draw now (text from the app's own timeline, at the position minus the delay; pictures from the stream). */
    val cues: StateFlow<SubtitleFrame> = trackControl.cues

    fun open(itemId: String, from: StartFrom) {
        if (ended) return
        if (token == null) {
            val t = activePlayback.begin(session) { end(it) }
            if (ended) return // refused: this session is not the active one
            token = t
            work.launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { onEvent(it) } }
            work.launch { while (true) { delay(TICK_MS); tick() } }
            work.launch { while (true) { delay(CUE_TICK_MS); if (phase == Phase.PLAYING) trackControl.refreshCues() } }
        }
        play(itemId, from)
    }

    fun playPause() {
        val c = current ?: return
        when (phase) {
            Phase.PLAYING -> pauseAndReport(c)
            Phase.PAUSED, Phase.BUFFERING -> engine.play()
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
        reload(c, stop = Stop.NORMAL) { resolver.resolve(session, plan.request.copy(quality = quality), TrackRules.burnInFormat(plan)) }
    }

    /** By identity; DIRECT_PLAY switches in the player, anything else asks again at the current position. Remembered for the series. */
    fun selectAudio(index: Int) = trackControl.selectAudio(index)

    /** Null: off. A text track is fetched and drawn by the app (no new stream); a picture track needs one except in DIRECT_PLAY. */
    fun selectSubtitle(index: Int?) = trackControl.selectSubtitle(index)

    /** "Let the server draw these subtitles" (offered when [PlaybackState.subtitleEffectsNotDrawn]). */
    fun burnInSubtitle(index: Int) = trackControl.burnInSubtitle(index)

    fun setSubtitleDelay(ms: Int) = trackControl.setDelay(ms)

    fun dismissSubtitleNotice() = trackControl.dismissNotice()

    fun setSleep(choice: SleepChoice) {
        sleep = SleepTimer.start(choice, clock.nowMs())
        publish()
    }

    /** The activity stopped (screen off, app left): pause, report, and no Up next plays on its own. */
    fun onHostStopped() {
        val c = current ?: return
        if (phase == Phase.PLAYING) pauseAndReport(c) else engine.pause()
        c.upNext?.cancel()
        publish()
    }

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
        if (reason == null) token?.let(activePlayback::release)
        token = null
        trackControl.clear()
        _state.value = PlaybackState.EMPTY
    }

    private fun play(itemId: String, from: StartFrom) {
        current?.let { stopReport(it, failed = false) }
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
            engine.setMediaMetadata(entry.item.name, entry.item.seriesName)
            publish()
            if (entry.item.kind == ItemKind.EPISODE) {
                work.launch { c.around = navigator.around(session, entry.item); if (current === c) publish() }
            }
            load(c, PlaybackRules.startAt(entry.userData?.positionMs ?: 0, entry.item.runtimeMs, from), Stop.NONE) { resolver.resolve(session, PlaybackRequest(itemId, quality)) }
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
        engine.load(EngineItem(resolved.route, resolved.plan.source, resolved.plan.itemId), atMs, playWhenReady = true)
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
        if (c.resolved == null || phase == Phase.FAILED || phase == Phase.RESOLVING) return
        val before = c.knownMs
        val now = positionOf(c)
        val finishAt = finishedAt(c)
        if (phase == Phase.PLAYING && PlaybackRules.crossedByPlayback(before, now, finishAt)) c.finished = true
        c.outro()?.let { if (now >= it.startMs) showUpNext(c) }
        c.upNext?.let { countdown ->
            if (phase != Phase.PAUSED) countdown.advance(TICK_MS)
            if (countdown.due) return play(countdown.itemId, StartFrom.RESUME)
        }
        if (SleepTimer.isDue(sleep, clock.nowMs())) { sleep = SleepState.Off; pauseAndReport(c) }
        c.reporter?.tick(snapshot(c))
        publish()
    }

    private fun onEnded(c: PlayingItem) {
        if (c.knownMs < (finishedAt(c) ?: Long.MAX_VALUE)) c.finished = true // played to the end, not seeked past the mark
        durationOf(c)?.let { c.knownMs = it }
        phase = Phase.ENDED
        if (sleep is SleepState.EndOfItem) {
            sleep = SleepState.Off
            engine.pause()
            return
        }
        showUpNext(c)
    }

    private fun onFailed(c: PlayingItem, failure: EngineFailure) {
        val resolved = c.resolved ?: return
        val plan = resolved.plan
        positionOf(c)
        when (failure) {
            EngineFailure.DECODER, EngineFailure.FORMAT_UNSUPPORTED ->
                if (!c.fellBack && plan.method != PlayMethod.TRANSCODE) {
                    c.fellBack = true
                    reload(c, Stop.FAILED) { resolver.fallBack(session, plan) }
                } else fail(c, AppError.Kind.PLAYBACK_FAILED)
            EngineFailure.NETWORK, EngineFailure.HTTP_UNAUTHORISED ->
                if (!c.reResolved) {
                    c.reResolved = true
                    reload(c, Stop.NORMAL) { resolver.resolve(session, plan.request, TrackRules.burnInFormat(plan)) }
                } else fail(c, if (failure == EngineFailure.NETWORK) AppError.Kind.NETWORK else AppError.Kind.UNAUTHORISED)
            EngineFailure.ROUTE_STALE ->
                if (c.reroutes++ < MAX_REROUTES) reload(c, Stop.NONE) { resolver.reroute(resolved) } else fail(c, AppError.Kind.OFFLINE)
            EngineFailure.NOT_SIGNED_IN -> return end(EndReason.SIGNED_OUT)
            EngineFailure.HTTP_OTHER, EngineFailure.BEHIND_LIVE_WINDOW, EngineFailure.OTHER -> fail(c, AppError.Kind.PLAYBACK_FAILED)
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
        if (c.upNextShown && toMs < (c.outro()?.startMs ?: durationOf(c) ?: Long.MAX_VALUE)) { c.upNext = null; c.upNextShown = false }
    }

    private fun showUpNext(c: PlayingItem) {
        if (c.upNextShown || sleep is SleepState.EndOfItem) return
        val next = c.around.next ?: return
        c.upNextShown = true
        c.upNext = UpNextCountdown(next.id, next.name, autoplayNext())
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

    private fun snapshot(c: PlayingItem): PlaybackReport {
        val plan = checkNotNull(c.resolved).plan
        return PlaybackReport(
            plan.itemId, plan.mediaSourceId, plan.playSessionId, plan.method, positionOf(c), paused = phase != Phase.PLAYING,
            audioIndex = plan.audioIndex, subtitleIndex = (c.subtitle as? SubtitleChoice.On)?.index,
        )
    }

    private fun durationOf(c: PlayingItem): Long? = c.durationMs(engine.durationMs)

    private fun finishedAt(c: PlayingItem): Long? = PlaybackRules.finishedAt(c.outro()?.startMs, durationOf(c))

    private fun publish() {
        if (ended) return
        val c = current
        val buffered = if (c?.resolved != null) engine.bufferedMs else 0
        _state.value = stateOf(session, c, phase, c?.let(::durationOf), buffered, quality, speed, sleep, error)
        trackControl.refreshCues()
    }

    private companion object {
        const val TICK_MS = 250L
        /** How often the drawn subtitles follow the position while playing. */
        const val CUE_TICK_MS = 50L
        const val ITEM_WAIT_MS = 5_000L
        const val RESTART_WINDOW_MS = 5_000L
        /** New routes in a row without the engine getting ready, before giving up as offline. */
        const val MAX_REROUTES = 3
    }
}
