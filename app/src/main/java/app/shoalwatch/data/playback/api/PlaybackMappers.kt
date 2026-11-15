package app.shoalwatch.data.playback.api

import app.shoalwatch.core.AppError
import app.shoalwatch.core.Ticks
import app.shoalwatch.core.displayText
import app.shoalwatch.data.api.JsonBodies
import app.shoalwatch.data.playback.AudioTrack
import app.shoalwatch.data.playback.MediaPaths
import app.shoalwatch.data.playback.MediaSegment
import app.shoalwatch.data.playback.SegmentKind
import app.shoalwatch.data.playback.StreamSource
import app.shoalwatch.data.playback.SubtitleKind
import app.shoalwatch.data.playback.SubtitleTrack
import app.shoalwatch.data.playback.TranscodeReason
import app.shoalwatch.data.playback.VideoRange
import app.shoalwatch.data.servers.JellyfinId
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/*
 * The S11 boundary for playback: response DTOs become validated models here and nowhere else (see "Playback endpoints" in
 * docs/ARCHITECTURE.md for the rules). Lists are decoded one element at a time, so one malformed source, stream or segment is
 * skipped and never costs the rest.
 */

private const val MAX_SOURCES = 50
private const val MAX_STREAMS = 200
private const val MAX_SEGMENTS = 200
private const val MAX_INDEX = 10_000L
private const val MAX_LABEL = 120
private const val MAX_BITRATE = 1_000_000_000L
private const val MAX_SIZE = 256L * 1024 * 1024 * 1024 // MediaSourceInfo.Size: 1..256 GiB, as the download facts
private const val MAX_SIDE = 16_384L
private const val MAX_REASONS = 32
private val MAX_TICKS = Ticks.fromMs(48L * 60 * 60 * 1000)

private val CODEC = Regex("[a-z0-9_.-]{1,32}")
private val CONTAINER = Regex("[a-z0-9_.,-]{1,64}")
private val LANGUAGE = Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})?")

private val PICTURE_CODECS = setOf("pgs", "pgssub", "hdmv_pgs_subtitle", "dvdsub", "dvd_subtitle", "vobsub", "dvbsub", "dvb_subtitle")
private val STYLED_CODECS = setOf("ass", "ssa")
private val TEXT_CODECS = setOf("srt", "subrip", "vtt", "webvtt")

/**
 * Jellyfin's codec name to the format the subtitle route takes (`MediaPaths.subtitleFile`). Any other TEXT codec (`mov_text`,
 * `text`, `ttml`...) is asked for as `srt`, which the server converts it to; picture codecs are never fetched as text.
 */
private val FILE_FORMATS = mapOf("srt" to "srt", "subrip" to "srt", "vtt" to "vtt", "webvtt" to "vtt", "ass" to "ass", "ssa" to "ssa")

private val REFUSALS = mapOf(
    "NotAllowed" to AppError.Kind.PLAYBACK_NOT_ALLOWED,
    "NoCompatibleStream" to AppError.Kind.NO_COMPATIBLE_STREAM,
    "RateLimitExceeded" to AppError.Kind.TOO_MANY_STREAMS,
)

private val REASONS = mapOf(
    "ContainerNotSupported" to TranscodeReason.CONTAINER,
    "VideoCodecNotSupported" to TranscodeReason.VIDEO_CODEC,
    "AudioCodecNotSupported" to TranscodeReason.AUDIO_CODEC,
    "SubtitleCodecNotSupported" to TranscodeReason.SUBTITLE,
    "ContainerBitrateExceedsLimit" to TranscodeReason.BITRATE,
    "VideoBitrateNotSupported" to TranscodeReason.BITRATE,
    "AudioBitrateNotSupported" to TranscodeReason.BITRATE,
    "VideoRangeTypeNotSupported" to TranscodeReason.VIDEO_RANGE,
    "VideoResolutionNotSupported" to TranscodeReason.RESOLUTION,
    "AudioChannelsNotSupported" to TranscodeReason.AUDIO_CHANNELS,
    "DirectPlayError" to TranscodeReason.DIRECT_PLAY_ERROR,
)

private val SEGMENT_KINDS = mapOf(
    "Intro" to SegmentKind.INTRO, "Outro" to SegmentKind.OUTRO, "Recap" to SegmentKind.RECAP,
    "Preview" to SegmentKind.PREVIEW, "Commercial" to SegmentKind.COMMERCIAL,
)

/** One element as [T], or null when it is the wrong shape (never echoed). */
private fun <T> JsonElement.decodeOrNull(deserializer: DeserializationStrategy<T>): T? = try {
    JsonBodies.JSON.decodeFromJsonElement(deserializer, this)
} catch (_: IllegalArgumentException) {
    null
}

internal fun PlaybackInfoResponseDto.toPlaybackInfo(): PlaybackInfo {
    val seen = HashSet<String>()
    val sources = mediaSources.orEmpty().asSequence()
        .mapNotNull { it.decodeOrNull(MediaSourceDto.serializer())?.toSourceInfo() }
        .filter { seen.add(it.id) }
        .take(MAX_SOURCES)
        .toList()
    return PlaybackInfo(playSessionId?.takeIf(MediaPaths::isSafeId), sources, errorCode?.let(REFUSALS::get))
}

private fun MediaSourceDto.toSourceInfo(): SourceInfo? {
    val sourceId = id?.takeIf(MediaPaths::isSafeId) ?: return null
    val streams = streams()
    val audio = streams.mapNotNull { it.toAudio() }
    val subtitles = streams.mapNotNull { it.toSubtitle() }
    val transcoding = MediaPaths.checkServerPath(transcodingUrl, hls = transcodingSubProtocol.equals("hls", ignoreCase = true))
    return SourceInfo(
        id = sourceId,
        container = container?.lowercase()?.takeIf(CONTAINER::matches),
        eTag = eTag?.takeIf(MediaPaths::isSafeId),
        runtimeMs = runTimeTicks?.takeIf { it in 0..MAX_TICKS }?.let(Ticks::toMs),
        bitrate = bitrate?.takeIf { it in 0..MAX_BITRATE }?.toInt(),
        supportsDirectPlay = supportsDirectPlay == true,
        supportsDirectStream = supportsDirectStream == true,
        supportsTranscoding = supportsTranscoding == true,
        transcoding = transcoding,
        transcodeReasons = transcoding?.let(::reasonsOf).orEmpty(),
        video = streams.firstOrNull { it.type == "Video" }?.toVideo(),
        audio = audio,
        subtitles = subtitles,
        defaultAudioIndex = defaultAudioStreamIndex?.takeIf { it in 0..MAX_INDEX }?.toInt()?.takeIf { i -> audio.any { it.index == i } },
        defaultSubtitleIndex = defaultSubtitleStreamIndex?.takeIf { it in 0..MAX_INDEX }?.toInt()?.takeIf { i -> subtitles.any { it.index == i } },
        hasSegments = hasSegments == true,
        sizeBytes = (size as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 1..MAX_SIZE },
    )
}

/** The well-formed streams with an index in 0..10,000, the first of each index only, at most 200. */
private fun MediaSourceDto.streams(): List<MediaStreamDto> {
    val seen = HashSet<Long>()
    return mediaStreams.orEmpty().asSequence()
        .mapNotNull { it.decodeOrNull(MediaStreamDto.serializer()) }
        .filter { s -> s.index != null && s.index in 0..MAX_INDEX && seen.add(s.index) }
        .take(MAX_STREAMS)
        .toList()
}

/** `TranscodeReasons` from the (already validated) transcoding URL's query: comma-separated reference names. */
private fun reasonsOf(source: StreamSource): Set<TranscodeReason> =
    source.query.filter { it.first.equals("TranscodeReasons", ignoreCase = true) }
        .flatMap { it.second.split(',') }.map { it.trim() }.filter { it.isNotEmpty() }
        .take(MAX_REASONS)
        .mapTo(LinkedHashSet()) { REASONS[it] ?: TranscodeReason.OTHER }

private fun MediaStreamDto.codecName(): String? = codec?.lowercase()?.takeIf(CODEC::matches)
private fun MediaStreamDto.languageTag(): String? = language?.takeIf(LANGUAGE::matches)
private fun MediaStreamDto.label(): String = displayText(displayTitle ?: title, MAX_LABEL)

private fun MediaStreamDto.toVideo() = VideoInfo(
    codec = codecName(),
    range = VideoRange.fromWire(videoRangeType),
    bitDepth = bitDepth?.takeIf { it in 1..16 }?.toInt(),
    width = width?.takeIf { it in 1..MAX_SIDE }?.toInt(),
    height = height?.takeIf { it in 1..MAX_SIDE }?.toInt(),
    dvProfile = dvProfile?.takeIf { it in 0..20 }?.toInt(),
)

private fun MediaStreamDto.toAudio(): AudioTrack? {
    if (type != "Audio") return null
    return AudioTrack(index!!.toInt(), label(), languageTag(), codecName(), channels?.takeIf { it in 1..32 }?.toInt(), isDefault == true)
}

/** A subtitle needs a well-formed codec: it is the format the app asks for, and decides how it is drawn. */
private fun MediaStreamDto.toSubtitle(): SubtitleTrack? {
    if (type != "Subtitle") return null
    val format = codecName() ?: return null
    val kind = when (format) {
        in PICTURE_CODECS -> SubtitleKind.PICTURE
        in STYLED_CODECS -> SubtitleKind.STYLED_TEXT
        in TEXT_CODECS -> SubtitleKind.TEXT
        else -> if (isTextSubtitleStream == true) SubtitleKind.TEXT else SubtitleKind.PICTURE
    }
    return SubtitleTrack(
        index!!.toInt(), label(), languageTag(), format, kind,
        forced = isForced == true, hearingImpaired = isHearingImpaired == true, isDefault = isDefault == true, external = isExternal == true,
        fileFormat = FILE_FORMATS[format] ?: "srt".takeIf { kind == SubtitleKind.TEXT },
    )
}

/** Known kinds only (`Unknown` and names this version does not know are dropped), a range inside 0..48 h, for [itemId] only, in start order. */
internal fun MediaSegmentQueryResultDto.toSegments(itemId: String): List<MediaSegment> =
    items.orEmpty().asSequence()
        .mapNotNull { it.decodeOrNull(MediaSegmentDto.serializer()) }
        .filter { it.itemId == null || JellyfinId.parseOrNull(it.itemId)?.value == itemId }
        .mapNotNull { dto ->
            val kind = dto.type?.let(SEGMENT_KINDS::get) ?: return@mapNotNull null
            val start = dto.startTicks ?: return@mapNotNull null
            val end = dto.endTicks ?: return@mapNotNull null
            if (start < 0 || end <= start || end > MAX_TICKS) null else MediaSegment(kind, Ticks.toMs(start), Ticks.toMs(end))
        }
        .take(MAX_SEGMENTS)
        .sortedBy { it.startMs }
        .toList()
