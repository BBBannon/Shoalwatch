package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.data.playback.engine.EngineFailure

/** What the controller does after an engine failure ([PlaybackRules.recovery]). */
internal sealed interface Recovery {
    /** Ask once as a forced transcode, at the same position. */
    data object FallBack : Recovery
    /** Ask again once (a new verified address; a dead token is invalidated on the way). */
    data object ReResolve : Recovery
    /** The same plan on a new route, asking the server nothing. */
    data object Reroute : Recovery
    data object SignedOut : Recovery
    data class Fail(val kind: AppError.Kind) : Recovery
}

/** The controller's pure rules (positions, the finished mark, speeds, recovery), kept apart so the state machine reads as one. */
internal object PlaybackRules {
    /** New routes in a row without the engine getting ready, before giving up as offline. */
    const val MAX_REROUTES = 3

    /**
     * DECODER or FORMAT_UNSUPPORTED: fall back once unless already a transcode (a LOCAL copy falls back once too: the resolver
     * re-checks it and streams); NETWORK or HTTP_UNAUTHORISED: ask again once;
     * ROUTE_STALE: a new route, up to [MAX_REROUTES] in a row without Ready, then OFFLINE; NOT_SIGNED_IN: end as signed out;
     * anything else PLAYBACK_FAILED.
     */
    fun recovery(failure: EngineFailure, method: PlayMethod, fellBack: Boolean, reResolved: Boolean, reroutes: Int): Recovery = when (failure) {
        EngineFailure.DECODER, EngineFailure.FORMAT_UNSUPPORTED ->
            if (!fellBack && method != PlayMethod.TRANSCODE) Recovery.FallBack else Recovery.Fail(AppError.Kind.PLAYBACK_FAILED)
        EngineFailure.NETWORK, EngineFailure.HTTP_UNAUTHORISED -> when {
            !reResolved -> Recovery.ReResolve
            failure == EngineFailure.NETWORK -> Recovery.Fail(AppError.Kind.NETWORK)
            else -> Recovery.Fail(AppError.Kind.UNAUTHORISED)
        }
        EngineFailure.ROUTE_STALE -> if (reroutes < MAX_REROUTES) Recovery.Reroute else Recovery.Fail(AppError.Kind.OFFLINE)
        EngineFailure.NOT_SIGNED_IN -> Recovery.SignedOut
        EngineFailure.HTTP_OTHER, EngineFailure.BEHIND_LIVE_WINDOW, EngineFailure.OTHER -> Recovery.Fail(AppError.Kind.PLAYBACK_FAILED)
    }

    /** The speeds offered; anything else is ignored. */
    val SPEEDS = setOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f)

    /** A jump larger than this between two ticks is a seek (from a headset, say), not playback crossing the finished mark. */
    const val MAX_TICK_STEP_MS = 3_000L

    /**
     * Where to start: [StartFrom.RESUME] starts at the saved position unless it is under 5% or over 90% of [runtimeMs] (then
     * the start); with no runtime known, at the saved position. The player seeks there; no start position goes to the server.
     */
    fun startAt(savedMs: Long, runtimeMs: Long?, from: StartFrom): Long = when {
        from == StartFrom.BEGINNING || savedMs <= 0 -> 0
        runtimeMs == null || runtimeMs <= 0 -> savedMs
        savedMs * 100 < runtimeMs * 5 || savedMs * 10 > runtimeMs * 9 -> 0
        else -> savedMs
    }

    /** SPEC 3.1: the credits (the Outro's start) or 90% of the runtime, whichever comes first; null when neither is known. */
    fun finishedAt(outroStartMs: Long?, durationMs: Long?): Long? = listOfNotNull(outroStartMs, durationMs?.let { it * 9 / 10 }).minOrNull()

    /** Playback (not a seek) carried the position from [beforeMs] to [nowMs] across [markMs]. */
    fun crossedByPlayback(beforeMs: Long, nowMs: Long, markMs: Long?): Boolean =
        markMs != null && beforeMs < markMs && nowMs >= markMs && nowMs - beforeMs <= MAX_TICK_STEP_MS
}
