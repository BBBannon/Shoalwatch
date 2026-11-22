package app.shoalwatch.data.discover.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Wire types for discovery (Milestone 11), checked against the 12.4.0 reference. Items themselves decode through the library's
// BaseItemDto. Lists hold raw elements so one bad element costs only itself.

/** The parts of `GET /Items/{id}` that discovery reads. */
@Serializable internal class ItemExtrasDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("People") val people: List<JsonElement>? = null,
    @SerialName("RemoteTrailers") val remoteTrailers: List<JsonElement>? = null,
    @SerialName("MediaSources") val mediaSources: List<JsonElement>? = null,
)

/** `BaseItemPerson`. */
@Serializable internal class PersonDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("Role") val role: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("PrimaryImageTag") val primaryImageTag: String? = null,
)

/** `MediaUrl`. */
@Serializable internal class MediaUrlDto(@SerialName("Url") val url: String? = null, @SerialName("Name") val name: String? = null)

/** `MediaSourceInfo`, only what a choice needs. */
@Serializable internal class MediaSourceDto(@SerialName("Id") val id: String? = null, @SerialName("Name") val name: String? = null)

/** `QueryFiltersLegacy` (`GET /Items/Filters`). */
@Serializable internal class FiltersDto(
    @SerialName("Years") val years: List<JsonElement>? = null,
    @SerialName("OfficialRatings") val officialRatings: List<JsonElement>? = null,
    @SerialName("Tags") val tags: List<JsonElement>? = null,
)
