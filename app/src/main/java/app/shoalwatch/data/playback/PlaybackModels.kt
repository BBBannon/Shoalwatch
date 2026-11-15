package app.shoalwatch.data.playback

/** How the server delivers the file: as it is, remuxed, or converted. */
enum class PlayMethod { DIRECT_PLAY, DIRECT_STREAM, TRANSCODE }

/** Streaming caps. MAXIMUM still sends a ceiling, because the server treats a missing one as its own default. */
enum class Quality(val maxBitrate: Int, val maxWidth: Int?) {
    MAXIMUM(200_000_000, null), MBPS_20(20_000_000, 1920), MBPS_8(8_000_000, 1920), MBPS_4(4_000_000, 1280), MBPS_1_5(1_500_000, 854)
}

/** From `MediaStream.VideoRangeType` (12.4.0 enum: Unknown, SDR, HDR10, HLG, DOVI, DOVIWithHDR10, DOVIWithHLG, DOVIWithSDR, DOVIWithEL, DOVIWithHDR10Plus, DOVIWithELHDR10Plus, DOVIInvalid, HDR10Plus). */
enum class VideoRange(val wire: String) {
    SDR("SDR"), HDR10("HDR10"), HDR10_PLUS("HDR10Plus"), HLG("HLG"), DOVI("DOVI"), DOVI_WITH_HDR10("DOVIWithHDR10"),
    DOVI_WITH_HLG("DOVIWithHLG"), DOVI_WITH_SDR("DOVIWithSDR"), DOVI_WITH_EL("DOVIWithEL"), DOVI_WITH_HDR10_PLUS("DOVIWithHDR10Plus"),
    DOVI_WITH_EL_HDR10_PLUS("DOVIWithELHDR10Plus"), DOVI_INVALID("DOVIInvalid"), UNKNOWN("Unknown");
    companion object { fun fromWire(s: String?): VideoRange = entries.firstOrNull { it.wire == s } ?: UNKNOWN }
}

/** The 12.4.0 `TranscodeReason` names this app explains; anything else is OTHER. */
enum class TranscodeReason { CONTAINER, VIDEO_CODEC, AUDIO_CODEC, SUBTITLE, BITRATE, VIDEO_RANGE, RESOLUTION, AUDIO_CHANNELS, DIRECT_PLAY_ERROR, OTHER }

data class AudioTrack(val index: Int, val label: String, val language: String?, val codec: String?, val channels: Int?, val isDefault: Boolean)

/** TEXT: SubRip/WebVTT. STYLED_TEXT: ASS/SSA. PICTURE: PGS, VobSub, DVB. */
enum class SubtitleKind { TEXT, STYLED_TEXT, PICTURE }

/** FILE: fetched as a file and drawn by the app. IN_STREAM: drawn by Media3 from the container. BURNED_IN: drawn into the picture by the server. */
enum class SubtitleDelivery { FILE, IN_STREAM, BURNED_IN }

data class SubtitleTrack(
    val index: Int, val label: String, val language: String?, val format: String, val kind: SubtitleKind,
    val forced: Boolean, val hearingImpaired: Boolean, val isDefault: Boolean, val external: Boolean,
    /** The format to fetch it as a file (`srt`, `vtt`, `ass`, `ssa`: what the subtitle route takes), or null: not fetchable as text. [format] is the codec name. */
    val fileFormat: String? = null,
)

/** A server path plus query, relative to the verified address. Never absolute, never carries a credential. */
data class StreamSource(val path: String, val query: List<Pair<String, String>>, val hls: Boolean) {
    override fun toString() = "StreamSource(<redacted>)"
}

data class PlaybackRequest(
    val itemId: String,
    val quality: Quality,
    val audioIndex: Int? = null,
    /** A PICTURE track to show in the stream or burn in. Text tracks are never sent: the app fetches them itself. */
    val pictureSubtitleIndex: Int? = null,
    /** The user asked the server to draw this subtitle (any kind). */
    val burnInSubtitleIndex: Int? = null,
    val forceTranscode: Boolean = false,
)

data class PlaybackPlan(
    val request: PlaybackRequest,
    val mediaSourceId: String,
    val playSessionId: String?,
    val method: PlayMethod,
    val source: StreamSource,
    val audioTracks: List<AudioTrack>,
    val subtitleTracks: List<SubtitleTrack>,
    val audioIndex: Int?,
    val runtimeMs: Long?,
    val videoRange: VideoRange,
    val transcodeReasons: Set<TranscodeReason>,
    val hasSegments: Boolean,
    val eTag: String?,
) {
    val itemId: String get() = request.itemId
    override fun toString() = "PlaybackPlan(method=$method, hls=${source.hls})"
}

enum class SegmentKind { INTRO, OUTRO, RECAP, PREVIEW, COMMERCIAL }
data class MediaSegment(val kind: SegmentKind, val startMs: Long, val endMs: Long)
