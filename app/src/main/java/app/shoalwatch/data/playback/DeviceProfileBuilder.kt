package app.shoalwatch.data.playback

import app.shoalwatch.data.playback.api.CodecProfileDto
import app.shoalwatch.data.playback.api.DeviceProfileDto
import app.shoalwatch.data.playback.api.DirectPlayProfileDto
import app.shoalwatch.data.playback.api.ProfileConditionDto
import app.shoalwatch.data.playback.api.SubtitleProfileDto
import app.shoalwatch.data.playback.api.TranscodingProfileDto

/**
 * The `DeviceProfile` sent with PlaybackInfo (pure), ported from AAFin's `DeviceProfileBuilder`; every property and enum value is
 * the 12.4.0 reference's (`DeviceProfile`, `DirectPlayProfile`, `TranscodingProfile`, `CodecProfile`, `ProfileCondition`,
 * `SubtitleProfile`). See "Device profile" in docs/ARCHITECTURE.md.
 *
 * - Direct play: the listed containers with whichever codecs this phone decodes; h264 and aac always (every Android phone decodes
 *   them, and they are the conversion target).
 * - Conversion: HLS with MPEG-TS segments, 8-bit SDR h264 and stereo aac (so any HDR source that is converted is tone-mapped
 *   by the server). The picture-size ceiling travels as conditions on the transcoding profile, because the PlaybackInfo request
 *   has no MaxWidth field and the server applies them only to video it re-encodes; with [build]'s `forceTranscode` it is also
 *   a codec condition, which the server applies to every conversion whatever its reason (AAFin's rule).
 * - Codec conditions: h264 at most 8-bit and the decoder's level; hevc, vp9 and av1 at most 10-bit when the 10-bit profile is
 *   decodable, else 8; every decodable codec lists the `VideoRangeType`s [HdrPolicy] allows (`EqualsAny`, joined with `|`).
 *   No condition is `IsRequired`.
 * - Subtitles: text formats are fetched as files (External); picture formats are embedded when direct playing and burned in
 *   when converting (Embed and Encode); a format the user asked the server to draw is Encode only.
 */
object DeviceProfileBuilder {
    const val DIRECT_PLAY_CONTAINERS = "mp4,m4v,mov,mkv,webm,ts,mpegts"
    val TEXT_SUBTITLES = listOf("srt", "subrip", "vtt", "webvtt", "ass", "ssa")
    val PICTURE_SUBTITLES = listOf("pgs", "pgssub", "dvdsub", "vobsub", "dvbsub")

    /** Jellyfin audio codec names in preference order. */
    private val AUDIO = listOf("aac", "mp3", "opus", "flac", "vorbis", "ac3", "eac3", "dts", "truehd")

    private const val NAME = "Shoalwatch"
    private const val VIDEO = "Video"

    /** [burnInFormat]: the format of a subtitle the user asked the server to draw; it is listed as Encode only. */
    internal fun build(caps: DeviceCapabilities, quality: Quality, forceTranscode: Boolean, burnInFormat: String? = null): DeviceProfileDto {
        val video = DirectPlayVideo.codecs(caps) // shared with the download check
        val audio = AUDIO.filter { it == "aac" || it in caps.audio }
        val ceiling = sizeConditions(quality)
        return DeviceProfileDto(
            name = NAME,
            maxStreamingBitrate = quality.maxBitrate,
            maxStaticBitrate = quality.maxBitrate,
            directPlayProfiles = listOf(
                DirectPlayProfileDto(DIRECT_PLAY_CONTAINERS, audio.joinToString(","), video.joinToString(",") { it.jellyfin }, VIDEO),
            ),
            transcodingProfiles = listOf(
                TranscodingProfileDto(
                    container = "ts", type = VIDEO, videoCodec = "h264", audioCodec = "aac", protocol = "hls", context = "Streaming",
                    maxAudioChannels = "2", copyTimestamps = false, enableSubtitlesInManifest = false, minSegments = 1, segmentLength = 6,
                    conditions = ceiling,
                ),
            ),
            containerProfiles = emptyList(),
            codecProfiles = video.map { codecProfile(it, caps) } +
                listOfNotNull(ceiling.takeIf { forceTranscode && it.isNotEmpty() }?.let { CodecProfileDto(VIDEO, null, it) }),
            subtitleProfiles = subtitleProfiles(burnInFormat?.lowercase()),
        )
    }

    private fun codecProfile(codec: VideoCodec, caps: DeviceCapabilities): CodecProfileDto {
        val ranges = HdrPolicy.directPlayRanges(codec, caps)
        val rangeWire = VideoRange.entries.filter { it in ranges }.joinToString("|") { it.wire }
        val conditions = when (codec) {
            VideoCodec.H264 -> listOf(atMost("VideoBitDepth", 8), atMost("VideoLevel", caps.h264MaxLevel))
            VideoCodec.HEVC -> listOf(atMost("VideoBitDepth", if (caps.hevcMain10) 10 else 8)) + limits(codec, caps)
            VideoCodec.VP9 -> listOf(atMost("VideoBitDepth", if (caps.vp9Profile2) 10 else 8)) + limits(codec, caps)
            VideoCodec.AV1 -> listOf(atMost("VideoBitDepth", if (caps.av1Main10) 10 else 8)) + limits(codec, caps)
        } + ProfileConditionDto("EqualsAny", "VideoRangeType", rangeWire, isRequired = false)
        return CodecProfileDto(VIDEO, codec.jellyfin, conditions)
    }

    /**
     * What the codec's decoder can keep up with: `Width`, `Height` and `VideoFramerate` at most its largest picture and rate there,
     * and for HEVC its `VideoLevel` when known. No data is [VideoLimits.CONSERVATIVE] (1080p30), never no limit.
     */
    private fun limits(codec: VideoCodec, caps: DeviceCapabilities): List<ProfileConditionDto> {
        val l = caps.videoLimits[codec] ?: VideoLimits.CONSERVATIVE
        return listOf(atMost("Width", l.maxWidth), atMost("Height", l.maxHeight), atMost("VideoFramerate", l.maxFrameRate)) +
            listOfNotNull(l.level?.takeIf { codec == VideoCodec.HEVC }?.let { atMost("VideoLevel", it) })
    }

    /** Width from the quality, height as 16:9 of it (1920 x 1080, 1280 x 720, 854 x 480); none at MAXIMUM. */
    private fun sizeConditions(quality: Quality): List<ProfileConditionDto> {
        val width = quality.maxWidth ?: return emptyList()
        return listOf(atMost("Width", width), atMost("Height", width * 9 / 16))
    }

    private fun subtitleProfiles(burnIn: String?): List<SubtitleProfileDto> {
        val profiles = TEXT_SUBTITLES.map { SubtitleProfileDto(it, "External") } +
            PICTURE_SUBTITLES.flatMap { listOf(SubtitleProfileDto(it, "Embed"), SubtitleProfileDto(it, "Encode")) }
        if (burnIn == null) return profiles
        return profiles.filterNot { it.format == burnIn } + SubtitleProfileDto(burnIn, "Encode")
    }

    private fun atMost(property: String, value: Int) = ProfileConditionDto("LessThanEqual", property, value.toString(), isRequired = false)
}
