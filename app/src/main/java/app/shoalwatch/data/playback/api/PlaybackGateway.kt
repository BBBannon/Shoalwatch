package app.shoalwatch.data.playback.api

import app.shoalwatch.core.AppError
import app.shoalwatch.data.playback.AudioTrack
import app.shoalwatch.data.playback.MediaSegment
import app.shoalwatch.data.playback.PlaybackReport
import app.shoalwatch.data.playback.StreamSource
import app.shoalwatch.data.playback.SubtitleTrack
import app.shoalwatch.data.playback.TranscodeReason
import app.shoalwatch.data.playback.VideoRange
import app.shoalwatch.data.servers.Session

/**
 * The server's playback endpoints as validated models (see "Playback endpoints" in docs/ARCHITECTURE.md), on `ServerApi`.
 * Ids passed in must be ones this app validated; anything else fails as `SERVER_NOT_FOUND` before a request is made.
 * Failures are [AppError]s from the authenticated call path. Internal: its request body is an internal wire type.
 */
internal interface PlaybackGateway {
    /**
     * POST /Items/{itemId}/PlaybackInfo, body PlaybackInfoDto (UserId, MaxStreamingBitrate, AudioStreamIndex, SubtitleStreamIndex,
     * DeviceProfile, EnableDirectPlay, EnableDirectStream, EnableTranscoding, AllowVideoStreamCopy, AllowAudioStreamCopy,
     * AlwaysBurnInSubtitleWhenTranscoding). `UserId` is always the session's user. Not retried (each answer opens a new play session).
     */
    suspend fun playbackInfo(s: Session, itemId: String, body: PlaybackInfoBody): PlaybackInfo
    suspend fun reportStart(s: Session, r: PlaybackReport)      // POST /Sessions/Playing        (PlaybackStartInfo)
    suspend fun reportProgress(s: Session, r: PlaybackReport)   // POST /Sessions/Playing/Progress (PlaybackProgressInfo)
    suspend fun reportStopped(s: Session, r: PlaybackReport)    // POST /Sessions/Playing/Stopped  (PlaybackStopInfo)
    suspend fun ping(s: Session, playSessionId: String)         // POST /Sessions/Playing/Ping?playSessionId= (required)
    suspend fun segments(s: Session, itemId: String): List<MediaSegment> // GET /MediaSegments/{itemId} (every kind; the mapper keeps the five)
    /** [format]: the track's `SubtitleTrack.fileFormat` (never its codec name). GET the subtitle file through ServerApi.getBytes: at most 8 MiB, Content-Type a `text` type (not `text/html`) or `application/x-subrip`, else SERVER_ERROR. */
    suspend fun subtitleFile(s: Session, itemId: String, mediaSourceId: String, index: Int, format: String): ByteArray
}

/** Validated PlaybackInfoResponse. [refusal] set when ErrorCode was NotAllowed / NoCompatibleStream / RateLimitExceeded. */
data class PlaybackInfo(val playSessionId: String?, val sources: List<SourceInfo>, val refusal: AppError.Kind?)

data class SourceInfo(
    val id: String, val container: String?, val eTag: String?, val runtimeMs: Long?, val bitrate: Int?,
    val supportsDirectPlay: Boolean, val supportsDirectStream: Boolean, val supportsTranscoding: Boolean,
    val transcoding: StreamSource?, val transcodeReasons: Set<TranscodeReason>,
    val video: VideoInfo?, val audio: List<AudioTrack>, val subtitles: List<SubtitleTrack>,
    val defaultAudioIndex: Int?, val defaultSubtitleIndex: Int?, val hasSegments: Boolean,
)

data class VideoInfo(val codec: String?, val range: VideoRange, val bitDepth: Int?, val width: Int?, val height: Int?, val dvProfile: Int?)
