package app.shoalwatch.data.playback

import app.shoalwatch.data.db.downloads.DownloadTrackEntity
import app.shoalwatch.data.downloads.SavedMedia
import app.shoalwatch.data.downloads.TrackType

/** The LOCAL plan of a saved copy, from what the download saved (pure). See "Offline playback" in docs/ARCHITECTURE.md. */
internal object SavedPlans {
    /**
     * A LOCAL plan: the audio tracks of `download_tracks`; the subtitles only where a file is saved (TEXT, STYLED_TEXT) or the
     * track is a PICTURE track inside an original's own file (an external picture track would need the server to draw it);
     * segments as known; no play session. Its request is a placeholder that the resolver replaces with the one asked for.
     * [PlaybackPlan.source] is never used to build an address: the engine opens [media] instead (`Resolved.engineItem`).
     */
    fun plan(itemId: String, media: SavedMedia, tracks: List<DownloadTrackEntity>, runtimeMs: Long?, hasSegments: Boolean): PlaybackPlan {
        val audio = tracks.filter { it.type == TrackType.AUDIO }.sortedBy { it.streamIndex }.map {
            AudioTrack(it.streamIndex, it.label, it.language, it.codec, it.channels, it.isDefault)
        }
        val subtitles = tracks.filter { it.type == TrackType.SUBTITLE && offered(it, media) }.sortedBy { it.streamIndex }.map {
            SubtitleTrack(
                index = it.streamIndex, label = it.label, language = it.language, format = it.codec ?: it.fileFormat.orEmpty(),
                kind = checkNotNull(it.kind), forced = it.forced, hearingImpaired = it.hearingImpaired, isDefault = it.isDefault,
                external = it.external, fileFormat = it.fileFormat.takeIf { _ -> it.fileSaved },
            )
        }
        val defaultAudio = (audio.firstOrNull { it.isDefault } ?: audio.firstOrNull())?.index
        return PlaybackPlan(
            request = PlaybackRequest(itemId, Quality.MAXIMUM),
            mediaSourceId = media.mediaSourceId,
            playSessionId = null,
            method = PlayMethod.LOCAL,
            source = StreamSource(path = "", query = emptyList(), hls = media.hls),
            audioTracks = audio,
            subtitleTracks = subtitles,
            audioIndex = defaultAudio,
            runtimeMs = runtimeMs,
            videoRange = VideoRange.UNKNOWN,
            transcodeReasons = emptySet(),
            hasSegments = hasSegments,
            eTag = null,
            defaultAudioIndex = defaultAudio,
            defaultSubtitleIndex = subtitles.firstOrNull { it.isDefault }?.index,
        )
    }

    private fun offered(t: DownloadTrackEntity, media: SavedMedia): Boolean = when (t.kind) {
        null -> false
        SubtitleKind.PICTURE -> !media.hls && !t.external
        SubtitleKind.TEXT, SubtitleKind.STYLED_TEXT -> t.fileSaved && t.fileFormat != null
    }
}
