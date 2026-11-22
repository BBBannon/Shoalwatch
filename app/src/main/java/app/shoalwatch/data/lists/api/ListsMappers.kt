package app.shoalwatch.data.lists.api

import app.shoalwatch.core.displayText
import app.shoalwatch.data.api.JsonBodies
import app.shoalwatch.data.library.api.toItem
import app.shoalwatch.data.library.api.toItemDto
import app.shoalwatch.data.library.api.toItemWithUserData
import app.shoalwatch.data.lists.ListsLimits
import app.shoalwatch.data.lists.Playlist
import app.shoalwatch.data.lists.PlaylistEntry
import app.shoalwatch.data.servers.JellyfinId
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

// The S11 boundary for playlists and collections: bounded lists, ids checked, names cleaned, one bad element costs only itself.

private val ENTRY_ID = Regex("[A-Za-z0-9-]{1,64}")

/** An id for a path segment or query value: the server's own `PlaylistItemId` shape, else null (never repaired). */
internal fun entryId(raw: String?): String? = raw?.takeIf { ENTRY_ID.matches(it) }

/**
 * Entries from one page of elements, at most [limit]: each needs a valid item and a valid entry id; a repeated entry id is dropped
 * (the same item twice is fine, it is two places).
 */
internal fun List<JsonElement>?.toEntries(limit: Int, seen: MutableSet<String> = HashSet()): List<PlaylistEntry> {
    val out = ArrayList<PlaylistEntry>()
    for (element in orEmpty()) {
        if (out.size >= limit) break
        val item = element.toItemDto()?.toItemWithUserData() ?: continue
        val raw = try {
            JsonBodies.JSON.decodeFromJsonElement(PlaylistEntryDto.serializer(), element).playlistItemId
        } catch (_: IllegalArgumentException) {
            null
        }
        val id = entryId(raw) ?: continue
        if (seen.add(id)) out += PlaylistEntry(id, item)
    }
    return out
}

/** The playlist's own item, or null when it is not the item asked for. */
internal fun JsonElement.toPlaylist(expectedId: String, entries: List<PlaylistEntry>): Playlist? {
    val item = toItemDto()?.toItem()?.takeIf { it.id == expectedId } ?: return null
    return Playlist(item, canDelete = canDeleteOf(this, expectedId), entries = entries)
}

/** True only when the element is the item asked for and says `CanDelete` is a real JSON `true`. */
internal fun canDeleteOf(element: JsonElement, id: String): Boolean {
    val dto = try {
        JsonBodies.JSON.decodeFromJsonElement(PlaylistRightsDto.serializer(), element)
    } catch (_: IllegalArgumentException) {
        return false
    }
    val said = (dto.canDelete as? JsonPrimitive)?.takeIf { !it.isString }?.content
    return JellyfinId.parseOrNull(dto.id)?.value == id && said == "true"
}

/** A name to send: cleaned, 1 to [ListsLimits.NAME] visible characters, else null. */
internal fun cleanName(raw: String): String? {
    val text = displayText(raw, ListsLimits.NAME + 1)
    return text.takeIf { it.isNotEmpty() && it.codePointCount(0, it.length) <= ListsLimits.NAME }
}
