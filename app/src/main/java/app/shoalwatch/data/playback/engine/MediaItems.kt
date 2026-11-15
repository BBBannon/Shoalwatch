package app.shoalwatch.data.playback.engine

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import app.shoalwatch.core.AppError
import app.shoalwatch.net.BlockedRequestException

/** What Media3 is handed, and how its errors are read. Pure apart from Media3's own value types. */
internal object MediaItems {
    /**
     * The URI is the route's verified address + the source's path and query (`MediaRoute.url`, which never holds a credential:
     * the token travels only in the `Authorization` header). HLS is marked as such; no subtitle configurations (text subtitles
     * are fetched and drawn by the app itself).
     */
    fun build(item: EngineItem, title: String?, subtitle: String?): MediaItem = MediaItem.Builder()
        .setUri(item.route.url(item.source).toString())
        .setMediaId(item.mediaId)
        .apply { if (item.source.hls) setMimeType(MimeTypes.APPLICATION_M3U8) }
        .setMediaMetadata(metadata(title, subtitle))
        .build()

    fun metadata(title: String?, subtitle: String?): MediaMetadata = MediaMetadata.Builder().setTitle(title).setSubtitle(subtitle).build()

    /**
     * By `errorCode` only, never the message (Media3's messages carry URIs). The one exception is a refusal by the media route,
     * found by type in the cause chain: `OFFLINE` (the route went stale) and `NOT_SIGNED_IN`; any other refusal is [EngineFailure.OTHER].
     */
    @OptIn(UnstableApi::class)
    fun failureOf(error: PlaybackException): EngineFailure {
        val causes = generateSequence(error.cause) { it.cause }.take(MAX_CAUSES).toList()
        causes.firstNotNullOfOrNull { (it as? BlockedRequestException)?.error?.kind }?.let { kind ->
            return when (kind) {
                AppError.Kind.OFFLINE -> EngineFailure.ROUTE_STALE
                AppError.Kind.NOT_SIGNED_IN -> EngineFailure.NOT_SIGNED_IN
                else -> EngineFailure.OTHER
            }
        }
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            -> EngineFailure.DECODER
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED, PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            -> EngineFailure.FORMAT_UNSUPPORTED
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            -> EngineFailure.NETWORK
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                if (causes.firstNotNullOfOrNull { (it as? HttpDataSource.InvalidResponseCodeException)?.responseCode } == 401) {
                    EngineFailure.HTTP_UNAUTHORISED
                } else EngineFailure.HTTP_OTHER
            PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> EngineFailure.BEHIND_LIVE_WINDOW
            else -> EngineFailure.OTHER
        }
    }

    private const val MAX_CAUSES = 16
}
