package app.shoalwatch.data.playback.api

import app.shoalwatch.core.AppError
import app.shoalwatch.core.Ticks
import app.shoalwatch.data.api.ServerApi
import app.shoalwatch.data.playback.MediaPaths
import app.shoalwatch.data.playback.MediaSegment
import app.shoalwatch.data.playback.PlayMethod
import app.shoalwatch.data.playback.PlaybackReport
import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.data.servers.Session

/**
 * [PlaybackGateway] on [ServerApi]. Paths are built only from ids that passed [JellyfinId] or [MediaPaths.isSafeId]; responses go
 * through `PlaybackMappers` and nothing but models leaves. Every name used was checked against the 12.4.0 reference (see
 * "Playback endpoints" in docs/ARCHITECTURE.md). Nothing here is retried except the idempotent subtitle GET (ServerApi's default):
 * reports are best-effort and their caller decides what a failure means.
 */
internal class PlaybackGatewayImpl(private val api: ServerApi) : PlaybackGateway {

    override suspend fun playbackInfo(s: Session, itemId: String, body: PlaybackInfoBody): PlaybackInfo {
        val json = PlaybackJson.encodeToString(PlaybackInfoBody.serializer(), body.copy(userId = s.userId, mediaSourceId = body.mediaSourceId?.takeIf(MediaPaths::isSourceId)))
        return api.postForJson(s, "/Items/${idOf(itemId)}/PlaybackInfo", listOf("userId" to s.userId), json, PlaybackInfoResponseDto.serializer())
            .toPlaybackInfo()
    }

    override suspend fun reportStart(s: Session, r: PlaybackReport) = api.post(s, "/Sessions/Playing", jsonBody = progressJson(r))

    override suspend fun reportProgress(s: Session, r: PlaybackReport) = api.post(s, "/Sessions/Playing/Progress", jsonBody = progressJson(r))

    override suspend fun reportStopped(s: Session, r: PlaybackReport) {
        checkIds(r)
        val dto = PlaybackStopDto(idOf(r.itemId), r.mediaSourceId, r.playSessionId, ticksOf(r.positionMs), r.failed)
        api.post(s, "/Sessions/Playing/Stopped", jsonBody = PlaybackJson.encodeToString(PlaybackStopDto.serializer(), dto))
    }

    override suspend fun ping(s: Session, playSessionId: String) {
        if (!MediaPaths.isSafeId(playSessionId)) throw notFound()
        api.post(s, "/Sessions/Playing/Ping", listOf("playSessionId" to playSessionId))
    }

    override suspend fun segments(s: Session, itemId: String): List<MediaSegment> {
        val id = idOf(itemId)
        // No includeSegmentTypes: the reference gives the array no style, so a server may not bind one comma-separated value; the
        // mapper keeps the five known kinds instead.
        return api.get(s, "/MediaSegments/$id", emptyList(), MediaSegmentQueryResultDto.serializer())
            .toSegments(id)
    }

    override suspend fun subtitleFile(s: Session, itemId: String, mediaSourceId: String, index: Int, format: String): ByteArray {
        val path = try {
            MediaPaths.subtitleFile(idOf(itemId), mediaSourceId, index, format)
        } catch (_: IllegalArgumentException) {
            throw notFound()
        }
        return api.getBytes(s, path, maxBytes = MAX_SUBTITLE_BYTES) { source, contentType ->
            if (!isSubtitleType(contentType)) throw AppError.of(AppError.Kind.SERVER_ERROR)
            source.readByteArray()
        }
    }

    private fun progressJson(r: PlaybackReport): String {
        checkIds(r)
        val dto = PlaybackProgressDto(
            itemId = idOf(r.itemId), mediaSourceId = r.mediaSourceId, playSessionId = r.playSessionId, positionTicks = ticksOf(r.positionMs),
            isPaused = r.paused, audioStreamIndex = r.audioIndex, subtitleStreamIndex = r.subtitleIndex, playMethod = wire(r.method),
        )
        return PlaybackJson.encodeToString(PlaybackProgressDto.serializer(), dto)
    }

    private fun checkIds(r: PlaybackReport) {
        if (r.method == PlayMethod.LOCAL) throw notFound() // RoutedPlaybackReports keeps these on the phone
        if (!MediaPaths.isSafeId(r.mediaSourceId) || (r.playSessionId != null && !MediaPaths.isSafeId(r.playSessionId))) throw notFound()
    }

    private fun ticksOf(positionMs: Long) = Ticks.fromMs(positionMs.coerceAtLeast(0))

    /** The reference's `PlayMethod` enum. */
    private fun wire(method: PlayMethod) = when (method) {
        PlayMethod.DIRECT_PLAY -> "DirectPlay"
        PlayMethod.DIRECT_STREAM -> "DirectStream"
        PlayMethod.TRANSCODE -> "Transcode"
        PlayMethod.LOCAL -> "DirectPlay" // never sent: a LOCAL report is refused by checkIds (Milestone 4 sends nothing for one)
    }

    /** `text/...` other than HTML, or SubRip's registered type; parameters ignored, any case. Missing is refused. */
    private fun isSubtitleType(contentType: String?): Boolean {
        val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return false
        return (type.startsWith("text/") && type != "text/html") || type == "application/x-subrip"
    }

    private fun idOf(raw: String): String = JellyfinId.parseOrNull(raw)?.value ?: throw notFound()

    private fun notFound() = AppError.of(AppError.Kind.SERVER_NOT_FOUND)

    private companion object {
        const val MAX_SUBTITLE_BYTES = 8L * 1024 * 1024
    }
}
