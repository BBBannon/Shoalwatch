package app.shoalwatch.data.manage.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Wire types for library management (Milestone 10), checked against the 12.4.0 reference. Every field is nullable with a default,
// decoded with JsonBodies.JSON (ignoreUnknownKeys only); numbers are JsonElement where a wrong type must cost only that value.
// NEVER declared, so never decoded, kept or shown: `ImageInfo.Path` (a server file path), `ImageInfo.BlurHash`,
// `RemoteSearchResult.ImageUrl` and `RemoteImageInfo.ThumbnailUrl` (third-party addresses the phone must not load, S7).

/** `RemoteSearchResult`. */
@Serializable internal class SearchResultDto(
    @SerialName("Name") val name: String? = null,
    @SerialName("ProviderIds") val providerIds: JsonElement? = null,
    @SerialName("ProductionYear") val productionYear: JsonElement? = null,
    @SerialName("PremiereDate") val premiereDate: String? = null,
    @SerialName("SearchProviderName") val searchProviderName: String? = null,
    @SerialName("Overview") val overview: String? = null,
)

/** `RemoteImageResult`. */
@Serializable internal class RemoteImageResultDto(
    @SerialName("Images") val images: List<JsonElement>? = null,
    @SerialName("TotalRecordCount") val total: JsonElement? = null,
    @SerialName("Providers") val providers: List<JsonElement>? = null,
)

/** `RemoteImageInfo`. [url] is handed back to the server only. */
@Serializable internal class RemoteImageDto(
    @SerialName("ProviderName") val providerName: String? = null,
    @SerialName("Url") val url: String? = null,
    @SerialName("Height") val height: JsonElement? = null,
    @SerialName("Width") val width: JsonElement? = null,
    @SerialName("CommunityRating") val communityRating: JsonElement? = null,
    @SerialName("VoteCount") val voteCount: JsonElement? = null,
    @SerialName("Language") val language: String? = null,
    @SerialName("Type") val type: String? = null,
)

/** `ImageInfo`. */
@Serializable internal class ImageInfoDto(
    @SerialName("ImageType") val imageType: String? = null,
    @SerialName("ImageIndex") val imageIndex: JsonElement? = null,
    @SerialName("Height") val height: JsonElement? = null,
    @SerialName("Width") val width: JsonElement? = null,
    @SerialName("Size") val size: JsonElement? = null,
)

/** `MetadataEditorInfo`: only the age ratings are used. */
@Serializable internal class MetadataEditorDto(
    @SerialName("ParentalRatingOptions") val parentalRatingOptions: List<JsonElement>? = null,
)

/** The two fields of `BaseItemDto` a delete reads: whose item it is, and `CanDelete` for the user asked about (any JSON tolerated). */
@Serializable internal class ItemRightsDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("CanDelete") val canDelete: JsonElement? = null,
)

/** `ParentalRating`. */
@Serializable internal class ParentalRatingDto(@SerialName("Name") val name: String? = null)

/** The body of `RemoteSearch/{Movie|Series}`: `{SearchInfo:{Name,Year}, ItemId}`. */
@Serializable internal class SearchQueryBody(
    @SerialName("SearchInfo") val searchInfo: SearchInfoBody,
    @SerialName("ItemId") val itemId: String,
)

@Serializable internal class SearchInfoBody(@SerialName("Name") val name: String, @SerialName("Year") val year: Int? = null)

/** The body of `RemoteSearch/Apply/{itemId}`: a `RemoteSearchResult` rebuilt from validated, typed fields. */
@Serializable internal class ApplyBody(
    @SerialName("Name") val name: String,
    @SerialName("ProductionYear") val productionYear: Int? = null,
    @SerialName("PremiereDate") val premiereDate: String? = null,
    @SerialName("ProviderIds") val providerIds: Map<String, String>,
    @SerialName("SearchProviderName") val searchProviderName: String,
)
