package app.shoalwatch.data.library.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Only the fields the app reads, from the Jellyfin 12.4.0 OpenAPI reference (BaseItemDto, UserItemDataDto,
// BaseItemDtoQueryResult). Every field is optional and nullable: the server is untrusted. Numbers that the reference
// types int32 are read as Long so an out-of-range value is only a range problem, which the mappers check. A value of
// the WRONG JSON TYPE would fail the decode of its item, so list containers hold each element (and an item's
// UserData, and the total) as a raw JsonElement and the mappers decode them one at a time: a bad element is skipped
// (a bad UserData is dropped, the item survives) and never costs the rest of the list.

@Serializable
internal class BaseItemDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("Name") val name: String? = null,
    @SerialName("SortName") val sortName: String? = null,
    @SerialName("Type") val type: String? = null,
    @SerialName("CollectionType") val collectionType: String? = null,
    @SerialName("ParentId") val parentId: String? = null,
    @SerialName("SeriesId") val seriesId: String? = null,
    @SerialName("SeriesName") val seriesName: String? = null,
    @SerialName("SeasonId") val seasonId: String? = null,
    @SerialName("IndexNumber") val indexNumber: Long? = null,
    @SerialName("ParentIndexNumber") val parentIndexNumber: Long? = null,
    @SerialName("Overview") val overview: String? = null,
    @SerialName("RunTimeTicks") val runTimeTicks: Long? = null,
    @SerialName("ProductionYear") val productionYear: Long? = null,
    @SerialName("PremiereDate") val premiereDate: String? = null,
    @SerialName("CommunityRating") val communityRating: Double? = null,
    @SerialName("OfficialRating") val officialRating: String? = null,
    @SerialName("ChildCount") val childCount: Long? = null,
    @SerialName("ImageTags") val imageTags: Map<String, String?>? = null,
    @SerialName("BackdropImageTags") val backdropImageTags: List<String?>? = null,
    @SerialName("SeriesPrimaryImageTag") val seriesPrimaryImageTag: String? = null,
    @SerialName("UserData") val userData: JsonElement? = null,
)

@Serializable
internal class UserItemDataDto(
    @SerialName("PlaybackPositionTicks") val playbackPositionTicks: Long? = null,
    @SerialName("PlayedPercentage") val playedPercentage: Double? = null,
    @SerialName("PlayCount") val playCount: Long? = null,
    @SerialName("IsFavorite") val isFavorite: Boolean? = null,
    @SerialName("Played") val played: Boolean? = null,
    @SerialName("LastPlayedDate") val lastPlayedDate: String? = null,
    @SerialName("UnplayedItemCount") val unplayedItemCount: Long? = null,
)

@Serializable
internal class BaseItemDtoQueryResult(
    @SerialName("Items") val items: List<JsonElement>? = null,
    @SerialName("TotalRecordCount") val totalRecordCount: JsonElement? = null,
)
