package app.shoalwatch.data.lists.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Wire types for playlists and collections (Milestone 11), checked against the 12.4.0 reference. Items themselves decode through
// the library's BaseItemDto; these carry only what it does not.

/** The playlist-only field of a `BaseItemDto` in `GET /Playlists/{id}/Items`. */
@Serializable internal class PlaylistEntryDto(@SerialName("PlaylistItemId") val playlistItemId: String? = null)

/** Whose item it is and `CanDelete` for the user asked about (any JSON tolerated). */
@Serializable internal class PlaylistRightsDto(
    @SerialName("Id") val id: String? = null,
    @SerialName("CanDelete") val canDelete: JsonElement? = null,
)

/** The body of `POST /Playlists`. */
@Serializable internal class NewPlaylistBody(
    @SerialName("Name") val name: String,
    @SerialName("Ids") val ids: List<String>,
    @SerialName("UserId") val userId: String,
    @SerialName("IsPublic") val isPublic: Boolean,
)

/** The body of `POST /Playlists/{id}`: a rename only. */
@Serializable internal class RenamePlaylistBody(@SerialName("Name") val name: String)
