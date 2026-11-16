package app.shoalwatch.data.playback

import app.shoalwatch.core.AppLog
import app.shoalwatch.core.MonotonicClock
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Decides WHEN to report; one per playing item, bound to the one [session] it was made for. All calls on the controller's
 * (main) dispatcher and none of them waits for the network: sends run one at a time in [scope] (the app scope, off the main
 * thread), in the order they were asked for. While one is in flight, a newer progress report replaces an older one still
 * waiting (the newest position is the one worth sending), and the stop replaces every waiting progress report and ping.
 * Ported from AAFin's `ProgressReporter`. See "Progress reports" in docs/ARCHITECTURE.md.
 *
 * - [onPlaying]: the first call is `started`; a later one after a pause is `progress`.
 * - [onPaused], [onSeeked], [onTracksChanged]: `progress` at once (a pause always as paused). Before `started` they send
 *   nothing (the engine is still preparing and its position means nothing yet); a seek then only moves where a stop would be.
 * - [tick]: `progress` once [intervalMs] has passed since the last report while playing; a paused TRANSCODE with a play session
 *   is pinged every [pingMs] instead, so the server keeps its conversion.
 * - [stop]: exactly once; every later call is ignored. Its position is the real last one: a stop that says 0 when the last
 *   thing heard was later (the engine let go of its position when the output changed, e.g. headphones gone) sends the last
 *   known position instead (research: "stop position sent as 0 on Bluetooth disconnect"). A stop before `started` is at
 *   [startPositionMs] (the resume point the player seeks to) or a seek made since, never at the engine's 0, so backing out
 *   before playback began leaves the saved resume point as it was.
 */
class ProgressReporter(
    private val reports: PlaybackReports,
    private val session: Session,
    private val scope: CoroutineScope,
    private val clock: MonotonicClock,
    private val startPositionMs: Long = 0,
    private val intervalMs: Long = 10_000,
    private val pingMs: Long = 30_000,
) {
    private sealed interface Send {
        data class Started(val r: PlaybackReport) : Send
        data class Progress(val r: PlaybackReport) : Send
        data class Ping(val playSessionId: String) : Send
        data class Stopped(val r: PlaybackReport, val finished: Boolean) : Send
    }

    private val lock = Any()
    private val waiting = ArrayDeque<Send>() // guarded by lock
    private var sending = false // guarded by lock: a drain is running

    // Touched only on the caller's (main) thread.
    private var started = false
    private var stopped = false
    private var paused = false
    private var lastReportAt = 0L
    private var last: PlaybackReport? = null

    fun onPlaying(snapshot: PlaybackReport) = heard(snapshot) { r ->
        when {
            !started -> { started = true; enqueue(Send.Started(r)) }
            paused -> enqueue(Send.Progress(r))
        }
        paused = false
    }

    fun onPaused(snapshot: PlaybackReport) = heardOnceStarted(snapshot) { r ->
        paused = true
        enqueue(Send.Progress(r.copy(paused = true)))
    }

    /** The one call whose position 0 is believed: the user seeked to the start. */
    fun onSeeked(snapshot: PlaybackReport) = heard(snapshot, seek = true) { r -> if (started) enqueue(Send.Progress(r)) }

    fun onTracksChanged(snapshot: PlaybackReport) = heardOnceStarted(snapshot) { r -> enqueue(Send.Progress(r)) }

    fun tick(snapshot: PlaybackReport) = heardOnceStarted(snapshot) { r ->
        val since = clock.nowMs() - lastReportAt
        val playSession = r.playSessionId
        when {
            !paused && since >= intervalMs -> enqueue(Send.Progress(r))
            paused && r.method == PlayMethod.TRANSCODE && playSession != null && since >= pingMs -> enqueue(Send.Ping(playSession))
        }
    }

    fun stop(snapshot: PlaybackReport, finished: Boolean) {
        if (stopped) return
        stopped = true
        val final = if (started) known(snapshot, seek = false) else snapshot.copy(positionMs = last?.positionMs ?: startPositionMs)
        synchronized(lock) { waiting.removeAll { it is Send.Progress || it is Send.Ping } }
        enqueue(Send.Stopped(final, finished))
    }

    /**
     * Every call but [stop]: ignored once stopped; otherwise its snapshot, as [known] makes it, is reported and becomes the last
     * known position.
     */
    private inline fun heard(snapshot: PlaybackReport, seek: Boolean = false, block: (PlaybackReport) -> Unit) {
        if (stopped) return
        val r = known(snapshot, seek)
        block(r)
        last = r
    }

    /** [heard], but ignored entirely until `started`: nothing is sent and the snapshot's position is not believed. */
    private inline fun heardOnceStarted(snapshot: PlaybackReport, block: (PlaybackReport) -> Unit) {
        if (started) heard(snapshot, block = block)
    }

    /**
     * A position of 0 (or less) from anything but a seek, for the same item, after a later one was heard (or, before anything
     * was heard, with a later start position), means the engine let go of its position (the output changed, it was released, or
     * the resume seek has not landed yet): the last known position stands.
     */
    private fun known(snapshot: PlaybackReport, seek: Boolean): PlaybackReport {
        if (seek || snapshot.positionMs > 0) return snapshot
        val before = last
        val known = if (before == null) startPositionMs else before.positionMs.takeIf { before.itemId == snapshot.itemId } ?: return snapshot
        return snapshot.copy(positionMs = known)
    }

    private fun enqueue(send: Send) {
        lastReportAt = clock.nowMs()
        val start = synchronized(lock) {
            if (send is Send.Progress && waiting.lastOrNull() is Send.Progress) waiting.removeLast()
            waiting.addLast(send)
            (!sending).also { sending = true }
        }
        if (start) scope.launch { drain() }
    }

    private suspend fun drain() {
        try {
            while (true) {
                val next = synchronized(lock) { waiting.removeFirstOrNull().also { if (it == null) sending = false } } ?: return
                deliver(next)
            }
        } catch (e: Throwable) {
            synchronized(lock) { sending = false } // cancelled with the scope: nothing more is sent
            throw e
        }
    }

    /** A seam that breaks its "never throws" contract still cannot stop the film or the reports after it. */
    private suspend fun deliver(send: Send) {
        try {
            when (send) {
                is Send.Started -> reports.started(session, send.r)
                is Send.Progress -> reports.progress(session, send.r)
                is Send.Ping -> reports.ping(session, send.playSessionId)
                is Send.Stopped -> reports.stopped(session, send.r, send.finished)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "report failed", e)
        }
    }

    private companion object {
        const val TAG = "ProgressReporter"
    }
}
