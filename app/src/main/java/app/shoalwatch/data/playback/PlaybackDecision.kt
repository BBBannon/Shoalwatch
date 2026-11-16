package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.data.playback.api.PlaybackInfo
import app.shoalwatch.data.playback.api.SourceInfo
import app.shoalwatch.data.servers.JellyfinId

/**
 * Turns the server's PlaybackInfo answer into a [PlaybackPlan] (pure; ported from AAFin's `PlaybackDecision`, without its
 * URL building: the plan holds a server path, never an address or a token). See "Playback decision" in docs/ARCHITECTURE.md.
 *
 * Everything in [PlaybackInfo] has already passed `PlaybackMappers`; the ids and the server path are checked once more here,
 * because the models are public data classes, so even a hand-built answer cannot produce a plan with an unvalidated id or path.
 */
object PlaybackDecision {
    /**
     * 1. refusal set -> throw AppError.of(refusal).
     * 2. First source (the server orders them); none -> NO_COMPATIBLE_STREAM.
     * 3. DIRECT_PLAY when supportsDirectPlay and nothing forces a transcode (forceTranscode or a burn-in):
     *    source = MediaPaths.directStream(itemId, source.id, source.eTag). A PICTURE subtitle the server would have to encode
     *    already makes its `SupportsDirectPlay` false (the profile lists picture formats Embed and Encode), so it needs no rule here.
     * 4. Else the server's transcoding path: DIRECT_STREAM when supportsDirectStream and nothing forces a transcode, else TRANSCODE.
     * 5. Else NO_COMPATIBLE_STREAM. (No static-stream guess for old servers: 10.10+ always sends TranscodingUrl when it can.)
     *
     * An item id that is not a normalised `JellyfinId` is SERVER_NOT_FOUND (as at the gateway). A source id that is not
     * `[A-Za-z0-9]{1,64}`, or a server path that no longer keeps `MediaPaths.checkServerPath`'s rules, is not usable; an unsafe
     * play session id or tag is dropped.
     */
    fun decide(request: PlaybackRequest, info: PlaybackInfo): PlaybackPlan {
        if (JellyfinId.parseOrNull(request.itemId)?.value != request.itemId) throw AppError.of(AppError.Kind.SERVER_NOT_FOUND)
        info.refusal?.let { throw AppError.of(it) }
        val source = info.sources.firstOrNull()?.takeIf { MediaPaths.isSafeId(it.id) } ?: throw noStream()
        val eTag = source.eTag?.takeIf(MediaPaths::isSafeId)
        val forced = forcesTranscode(request)
        val serverPath = source.transcoding?.takeIf(MediaPaths::isServerSource)
        val (method, stream) = when {
            source.supportsDirectPlay && !forced -> PlayMethod.DIRECT_PLAY to MediaPaths.directStream(request.itemId, source.id, eTag)
            serverPath != null -> (if (source.supportsDirectStream && !forced) PlayMethod.DIRECT_STREAM else PlayMethod.TRANSCODE) to serverPath
            else -> throw noStream()
        }
        return PlaybackPlan(
            request = request,
            mediaSourceId = source.id,
            playSessionId = info.playSessionId?.takeIf(MediaPaths::isSafeId),
            method = method,
            source = stream,
            audioTracks = source.audio,
            subtitleTracks = source.subtitles,
            audioIndex = audioIndex(request, source),
            runtimeMs = source.runtimeMs,
            videoRange = source.video?.range ?: VideoRange.UNKNOWN,
            transcodeReasons = if (method == PlayMethod.DIRECT_PLAY) emptySet() else source.transcodeReasons,
            hasSegments = source.hasSegments,
            eTag = eTag,
            defaultAudioIndex = source.defaultAudioIndex,
            defaultSubtitleIndex = source.defaultSubtitleIndex,
        )
    }

    /** True when the answer would burn in a subtitle but offers no transcoding path: ask again with direct play and direct stream off. */
    fun needsForcedTranscode(request: PlaybackRequest, info: PlaybackInfo): Boolean {
        if (request.forceTranscode || request.burnInSubtitleIndex == null || info.refusal != null) return false
        val source = info.sources.firstOrNull() ?: return false
        return source.transcoding?.takeIf(MediaPaths::isServerSource) == null
    }

    private fun forcesTranscode(request: PlaybackRequest) = request.forceTranscode || request.burnInSubtitleIndex != null

    /** The asked track if this source has it, else the server's default, else the first audio track. */
    private fun audioIndex(request: PlaybackRequest, source: SourceInfo): Int? =
        request.audioIndex?.takeIf { i -> source.audio.any { it.index == i } } ?: source.defaultAudioIndex ?: source.audio.firstOrNull()?.index

    private fun noStream() = AppError.of(AppError.Kind.NO_COMPATIBLE_STREAM)
}
