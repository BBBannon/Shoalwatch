package app.shoalwatch.data.downloads.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Only the fields the download facts read, from the Jellyfin 12.4.0 reference (BaseItemDto.CanDownload, BaseItemDto.MediaSources,
// MediaSourceInfo.Id/ETag/Container/Size). Every field is optional and untrusted. Fields whose JSON type the mapper must check
// itself (CanDownload must be a real boolean, Size a whole number, MediaSources an array) are kept as raw JsonElements, and each
// element is decoded on its own, so one malformed item or source never costs the rest (as in the library DTOs).

@Serializable
internal class DownloadItemsResult(@SerialName("Items") val items: List<JsonElement>? = null)

@Serializable
internal class DownloadItemDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("CanDownload") val canDownload: JsonElement? = null,
    @SerialName("MediaSources") val mediaSources: JsonElement? = null,
)

@Serializable
internal class DownloadSourceDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("ETag") val eTag: String? = null,
    @SerialName("Container") val container: String? = null,
    @SerialName("Size") val size: JsonElement? = null,
)
