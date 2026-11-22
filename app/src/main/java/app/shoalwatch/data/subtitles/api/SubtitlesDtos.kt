package app.shoalwatch.data.subtitles.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Wire types for remote subtitles (Milestone 11), checked against the 12.4.0 reference. Lists hold raw elements so one bad element
// costs only itself. The reference's other fields (Author, Comment, DateCreated, FrameRate...) are not read, so none can be kept.

/** `RemoteSubtitleInfo`. */
@Serializable internal class RemoteSubtitleDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("ProviderName") val providerName: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Format") val format: String? = null,
    @SerialName("CommunityRating") val communityRating: Double? = null,
    @SerialName("DownloadCount") val downloadCount: Long? = null,
    @SerialName("HearingImpaired") val hearingImpaired: Boolean? = null,
    @SerialName("Forced") val forced: Boolean? = null,
)

/** The part of `GET /Items/{id}` read for the subtitle count. */
@Serializable internal class SubtitleItemDto(@SerialName("MediaStreams") val mediaStreams: List<JsonElement>? = null)

@Serializable internal class StreamTypeDto(@SerialName("Type") val type: String? = null)

/** The part of `GET /Users/Me` read for the language. */
@Serializable internal class MeLanguageDto(@SerialName("Configuration") val configuration: ConfigurationDto? = null)

@Serializable internal class ConfigurationDto(@SerialName("SubtitleLanguagePreference") val subtitleLanguagePreference: String? = null)
