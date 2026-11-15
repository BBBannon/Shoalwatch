package app.shoalwatch.data.downloads

import app.shoalwatch.core.AppError
import app.shoalwatch.data.playback.AudioTrack
import app.shoalwatch.data.playback.MediaPaths
import app.shoalwatch.data.playback.SubtitleTrack
import app.shoalwatch.data.playback.api.PlaybackInfo

/** Can the original file play on this phone as it is (pure, over a `checkOriginal` answer)? */
internal sealed interface OriginalCheck {
    /** [container] is the extension it will be saved with (one of [DownloadPaths.MEDIA_EXTENSIONS]). */
    data class Playable(
        val sourceId: String, val container: String, val sizeBytes: Long?, val eTag: String?,
        val audio: List<AudioTrack>, val subtitles: List<SubtitleTrack>, val defaultAudio: Int?,
    ) : OriginalCheck

    data object NotPlayableHere : OriginalCheck
}

internal object OriginalChecks {
    /**
     * The server's first source: Playable when it `SupportsDirectPlay` (with this phone's profile) and its container is one the
     * app saves; otherwise (or with no source) NotPlayableHere. A refusal (`NotAllowed`, `NoCompatibleStream`, `RateLimitExceeded`)
     * is thrown as its AppError. Jellyfin reports MP4 as the list `mov,mp4,m4a,...`: `mp4` is chosen when listed, else the
     * first listed name the app saves.
     */
    fun of(info: PlaybackInfo): OriginalCheck {
        info.refusal?.let { throw AppError.of(it) }
        val source = info.sources.firstOrNull() ?: return OriginalCheck.NotPlayableHere
        if (!source.supportsDirectPlay || !MediaPaths.isSafeId(source.id)) return OriginalCheck.NotPlayableHere
        val names = source.container?.split(',')?.map { it.trim() }.orEmpty()
        val container = (if ("mp4" in names) "mp4" else names.firstOrNull { it in DownloadPaths.MEDIA_EXTENSIONS })
            ?: return OriginalCheck.NotPlayableHere
        return OriginalCheck.Playable(
            sourceId = source.id, container = container, sizeBytes = source.sizeBytes, eTag = source.eTag,
            audio = source.audio, subtitles = source.subtitles, defaultAudio = source.defaultAudioIndex,
        )
    }
}
