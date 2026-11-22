package app.shoalwatch.data.playback.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/*
 * Wire shapes of the playback endpoints, named exactly as the 12.4.0 reference's schemas (`PlaybackInfoDto`, `DeviceProfile` and its
 * parts, `PlaybackInfoResponse`, `MediaSourceInfo`, `MediaStream`, `PlaybackStartInfo`/`PlaybackProgressInfo`/`PlaybackStopInfo`,
 * `MediaSegmentDtoQueryResult`). Responses are untrusted: every field is nullable with a default, numbers that are range-checked
 * are read as Long, and lists are raw elements decoded one at a time (`PlaybackMappers`). Nothing here leaves the package.
 */

/** The one encoder for bodies we send. `JsonBodies` stays the one decoder. Nulls are left out, defaults are sent. */
internal val PlaybackJson = Json { explicitNulls = false; encodeDefaults = true }

/** `PlaybackInfoDto`. `UserId` is always the session's user: the gateway sets it. */
@Serializable
internal data class PlaybackInfoBody(
    @SerialName("UserId") val userId: String,
    @SerialName("MaxStreamingBitrate") val maxStreamingBitrate: Int,
    @SerialName("AudioStreamIndex") val audioStreamIndex: Int? = null,
    @SerialName("SubtitleStreamIndex") val subtitleStreamIndex: Int? = null,
    @SerialName("DeviceProfile") val deviceProfile: DeviceProfileDto,
    @SerialName("EnableDirectPlay") val enableDirectPlay: Boolean,
    @SerialName("EnableDirectStream") val enableDirectStream: Boolean,
    @SerialName("EnableTranscoding") val enableTranscoding: Boolean,
    @SerialName("AllowVideoStreamCopy") val allowVideoStreamCopy: Boolean,
    @SerialName("AllowAudioStreamCopy") val allowAudioStreamCopy: Boolean,
    @SerialName("AlwaysBurnInSubtitleWhenTranscoding") val alwaysBurnInSubtitleWhenTranscoding: Boolean,
    /** The version chosen for this play (32 hex characters); left out when null. */
    @SerialName("MediaSourceId") val mediaSourceId: String? = null,
) {
    override fun toString() = "PlaybackInfoBody(<redacted>)"
}

/** `DeviceProfile` (filled by `DeviceProfileBuilder`). Enum-valued fields hold the reference's wire names. */
@Serializable
internal data class DeviceProfileDto(
    @SerialName("Name") val name: String,
    @SerialName("MaxStreamingBitrate") val maxStreamingBitrate: Int,
    @SerialName("MaxStaticBitrate") val maxStaticBitrate: Int,
    @SerialName("DirectPlayProfiles") val directPlayProfiles: List<DirectPlayProfileDto> = emptyList(),
    @SerialName("TranscodingProfiles") val transcodingProfiles: List<TranscodingProfileDto> = emptyList(),
    @SerialName("ContainerProfiles") val containerProfiles: List<JsonObject> = emptyList(),
    @SerialName("CodecProfiles") val codecProfiles: List<CodecProfileDto> = emptyList(),
    @SerialName("SubtitleProfiles") val subtitleProfiles: List<SubtitleProfileDto> = emptyList(),
)

@Serializable
internal data class DirectPlayProfileDto(
    @SerialName("Container") val container: String,
    @SerialName("AudioCodec") val audioCodec: String,
    @SerialName("VideoCodec") val videoCodec: String,
    @SerialName("Type") val type: String,
)

@Serializable
internal data class TranscodingProfileDto(
    @SerialName("Container") val container: String,
    @SerialName("Type") val type: String,
    @SerialName("VideoCodec") val videoCodec: String,
    @SerialName("AudioCodec") val audioCodec: String,
    @SerialName("Protocol") val protocol: String,
    @SerialName("Context") val context: String,
    @SerialName("MaxAudioChannels") val maxAudioChannels: String,
    @SerialName("CopyTimestamps") val copyTimestamps: Boolean,
    @SerialName("EnableSubtitlesInManifest") val enableSubtitlesInManifest: Boolean,
    @SerialName("MinSegments") val minSegments: Int,
    @SerialName("SegmentLength") val segmentLength: Int,
    @SerialName("Conditions") val conditions: List<ProfileConditionDto>,
)

@Serializable
internal data class CodecProfileDto(
    @SerialName("Type") val type: String,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Conditions") val conditions: List<ProfileConditionDto>,
    @SerialName("ApplyConditions") val applyConditions: List<ProfileConditionDto> = emptyList(),
)

@Serializable
internal data class ProfileConditionDto(
    @SerialName("Condition") val condition: String,
    @SerialName("Property") val property: String,
    @SerialName("Value") val value: String,
    @SerialName("IsRequired") val isRequired: Boolean,
)

@Serializable
internal data class SubtitleProfileDto(
    @SerialName("Format") val format: String,
    @SerialName("Method") val method: String,
)

// ---- responses (untrusted)

@Serializable
internal class PlaybackInfoResponseDto(
    @SerialName("MediaSources") val mediaSources: List<JsonElement>? = null,
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("ErrorCode") val errorCode: String? = null,
)

@Serializable
internal class MediaSourceDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("ETag") val eTag: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("Bitrate") val bitrate: Long? = null,
    @SerialName("SupportsDirectPlay") val supportsDirectPlay: Boolean? = null,
    @SerialName("SupportsDirectStream") val supportsDirectStream: Boolean? = null,
    @SerialName("SupportsTranscoding") val supportsTranscoding: Boolean? = null,
    @SerialName("TranscodingUrl") val transcodingUrl: String? = null,
    @SerialName("TranscodingSubProtocol") val transcodingSubProtocol: String? = null,
    @SerialName("MediaStreams") val mediaStreams: List<JsonElement>? = null,
    @SerialName("DefaultAudioStreamIndex") val defaultAudioStreamIndex: Long? = null,
    @SerialName("DefaultSubtitleStreamIndex") val defaultSubtitleStreamIndex: Long? = null,
    @SerialName("HasSegments") val hasSegments: Boolean? = null,
    /** Raw: only a whole JSON number counts (a fractional or string size must not cost the source). */
    @SerialName("Size") val size: JsonElement? = null,
)

@Serializable
internal class MediaStreamDto(
    @SerialName("Type") val type: String? = null,
    @SerialName("Index") val index: Long? = null,
    @SerialName("Codec") val codec: String? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("DisplayTitle") val displayTitle: String? = null,
    @SerialName("Title") val title: String? = null,
    @SerialName("IsDefault") val isDefault: Boolean? = null,
    @SerialName("IsForced") val isForced: Boolean? = null,
    @SerialName("IsHearingImpaired") val isHearingImpaired: Boolean? = null,
    @SerialName("IsExternal") val isExternal: Boolean? = null,
    @SerialName("IsTextSubtitleStream") val isTextSubtitleStream: Boolean? = null,
    @SerialName("Channels") val channels: Long? = null,
    @SerialName("BitDepth") val bitDepth: Long? = null,
    @SerialName("Width") val width: Long? = null,
    @SerialName("Height") val height: Long? = null,
    @SerialName("VideoRangeType") val videoRangeType: String? = null,
    @SerialName("DvProfile") val dvProfile: Long? = null,
)

@Serializable
internal class MediaSegmentQueryResultDto(@SerialName("Items") val items: List<JsonElement>? = null)

@Serializable
internal class MediaSegmentDto(
    @SerialName("ItemId") val itemId: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("StartTicks") val startTicks: Long? = null,
    @SerialName("EndTicks") val endTicks: Long? = null,
)

// ---- reports (sent)

/** `PlaybackStartInfo` and `PlaybackProgressInfo` have the same fields this app sends. */
@Serializable
internal class PlaybackProgressDto(
    @SerialName("ItemId") val itemId: String,
    @SerialName("MediaSourceId") val mediaSourceId: String,
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("IsPaused") val isPaused: Boolean,
    @SerialName("IsMuted") val isMuted: Boolean = false,
    @SerialName("CanSeek") val canSeek: Boolean = true,
    @SerialName("AudioStreamIndex") val audioStreamIndex: Int? = null,
    @SerialName("SubtitleStreamIndex") val subtitleStreamIndex: Int? = null,
    @SerialName("PlayMethod") val playMethod: String,
    @SerialName("RepeatMode") val repeatMode: String = "RepeatNone",
    @SerialName("PlaybackOrder") val playbackOrder: String = "Default",
)

/** `PlaybackStopInfo`. */
@Serializable
internal class PlaybackStopDto(
    @SerialName("ItemId") val itemId: String,
    @SerialName("MediaSourceId") val mediaSourceId: String,
    @SerialName("PlaySessionId") val playSessionId: String? = null,
    @SerialName("PositionTicks") val positionTicks: Long,
    @SerialName("Failed") val failed: Boolean,
)
