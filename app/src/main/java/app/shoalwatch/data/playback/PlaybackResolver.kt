package app.shoalwatch.data.playback

import app.shoalwatch.data.api.MediaRoute
import app.shoalwatch.data.playback.api.PlaybackGateway
import app.shoalwatch.data.playback.api.PlaybackInfo
import app.shoalwatch.data.playback.api.PlaybackInfoBody
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Asks the server how to play an item (PlaybackInfo, with this phone's device profile), decides ([PlaybackDecision]) and hands
 * back the plan with a fresh [MediaRoute]. Ported from AAFin's `PlaybackResolver`. Failures are the gateway's or the decision's
 * [app.shoalwatch.core.AppError], unchanged. See "Playback decision" in docs/ARCHITECTURE.md.
 *
 * [routes] is `serverApi::mediaRoute` in production and a fake in tests. [io]: where the device capabilities are read (the
 * platform's codec list is not a main-thread read).
 */
class PlaybackResolver internal constructor(
    private val gateway: PlaybackGateway,
    private val capabilities: CodecSupport,
    private val routes: suspend (Session) -> MediaRoute,
    private val io: CoroutineDispatcher = Dispatchers.Default,
) {
    /**
     * Ask, decide, and (when [PlaybackDecision.needsForcedTranscode]) ask once more as a forced transcode. Throws AppError.
     * [burnInFormat]: the format (`SubtitleTrack.format`) of `request.burnInSubtitleIndex`, from the plan the user chose it in;
     * the device profile then lists it as Encode only. The route is asked for after the decision, so it is to the address
     * verified now.
     */
    suspend fun resolve(s: Session, request: PlaybackRequest, burnInFormat: String? = null): Resolved {
        var effective = request
        var info = ask(s, effective, burnInFormat)
        if (PlaybackDecision.needsForcedTranscode(effective, info)) {
            effective = effective.copy(forceTranscode = true)
            info = ask(s, effective, burnInFormat)
        }
        return planned(s, effective, info)
    }

    /**
     * After the player failed to decode a direct play / direct stream: the same request as a forced transcode, asked exactly
     * once. Called at most once per item by the controller.
     */
    suspend fun fallBack(s: Session, failed: PlaybackPlan): Resolved {
        val request = failed.request.copy(forceTranscode = true)
        val format = request.burnInSubtitleIndex?.let { i -> failed.subtitleTracks.firstOrNull { it.index == i }?.format }
        return planned(s, request, ask(s, request, format))
    }

    /**
     * The same plan with a new route, asking the server nothing: for when the old route refused to send because it went stale
     * (`BlockedRequestException` with `OFFLINE` after a network change or a settings change). A route for a signed-out account
     * fails here as `NOT_SIGNED_IN`, sending nothing.
     */
    suspend fun reroute(resolved: Resolved): Resolved = Resolved(resolved.plan, route(resolved.route.session))

    /**
     * Can the original play here as it is? PlaybackInfo with this phone's profile at MAXIMUM, EnableDirectPlay true and
     * EnableDirectStream, EnableTranscoding and both stream copies false, so the server sets up no conversion. Used by the
     * download planner (Milestone 4); makes no route. Throws AppError (the gateway's, unchanged).
     */
    internal suspend fun checkOriginal(s: Session, itemId: String): PlaybackInfo {
        val profile = withContext(io) { DeviceProfileBuilder.build(capabilities.read(), Quality.MAXIMUM, false, null) }
        val body = PlaybackInfoBody(
            userId = s.userId,
            maxStreamingBitrate = Quality.MAXIMUM.maxBitrate,
            audioStreamIndex = null,
            subtitleStreamIndex = -1,
            deviceProfile = profile,
            enableDirectPlay = true,
            enableDirectStream = false,
            enableTranscoding = false,
            allowVideoStreamCopy = false,
            allowAudioStreamCopy = false,
            alwaysBurnInSubtitleWhenTranscoding = false,
        )
        return gateway.playbackInfo(s, itemId, body)
    }

    private suspend fun planned(s: Session, request: PlaybackRequest, info: PlaybackInfo): Resolved =
        Resolved(PlaybackDecision.decide(request, info), route(s))

    private suspend fun route(s: Session): MediaRoute = routes(s).also { check(it.session == s) { "Route for another session." } }

    private suspend fun ask(s: Session, request: PlaybackRequest, burnInFormat: String?): PlaybackInfo {
        val burnIn = request.burnInSubtitleIndex
        val direct = !request.forceTranscode && burnIn == null
        // A burn-in is always a conversion, and the server applies the picture-size ceiling only as a codec condition (AAFin's
        // finding), so it gets the forced transcode's ceiling too.
        val profile = withContext(io) {
            DeviceProfileBuilder.build(capabilities.read(), request.quality, !direct, burnInFormat.takeIf { burnIn != null })
        }
        val body = PlaybackInfoBody(
            userId = s.userId,
            maxStreamingBitrate = request.quality.maxBitrate,
            audioStreamIndex = request.audioIndex,
            // -1 = none. Leaving it unset lets the server pick one, which can force a burn-in transcode (AAFin's finding).
            subtitleStreamIndex = burnIn ?: request.pictureSubtitleIndex ?: -1,
            deviceProfile = profile,
            enableDirectPlay = direct,
            enableDirectStream = direct,
            enableTranscoding = true,
            allowVideoStreamCopy = true,
            allowAudioStreamCopy = true,
            alwaysBurnInSubtitleWhenTranscoding = burnIn != null,
        )
        // StartTimeTicks is not sent: every stream starts at zero and the player seeks (HLS playlists cover the whole runtime).
        return gateway.playbackInfo(s, request.itemId, body)
    }
}

/** A plan with the route its bytes come from. Never logged; both halves redact themselves. */
class Resolved(val plan: PlaybackPlan, val route: MediaRoute) {
    override fun toString() = "Resolved(<redacted>)"
}
