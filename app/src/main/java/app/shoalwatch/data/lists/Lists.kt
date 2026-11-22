package app.shoalwatch.data.lists

import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.servers.Session

/**
 * Playlists and collections as the screens use them (Milestone 11): a token-free [Session] in, validated models out. Live only
 * (nothing is cached except the in-memory collection index behind [collectionsOf]). Implemented by `api/ListsGateway` only.
 * Ids must be normalised `JellyfinId`s, else `SERVER_NOT_FOUND` with nothing sent; names are 1 to [ListsLimits.NAME] visible
 * characters, else `IllegalArgumentException` with nothing sent. Every change is sent once, never retried, and a refusal (403, or a
 * 401 the server confirms is not the sign-in ending) is an `AppError` of kind `FORBIDDEN` that never signs the profile out; the
 * screens word it "The server didn't let this profile change that." from a string resource.
 */
interface Lists {
    suspend fun playlists(s: Session): List<ItemWithUserData>

    /** The playlist and its entries (at most [ListsLimits.ENTRIES], read [ListsLimits.PAGE] at a time). */
    suspend fun playlist(s: Session, playlistId: String): Playlist

    suspend fun createPlaylist(s: Session, name: String, itemIds: List<String>)
    suspend fun renamePlaylist(s: Session, playlistId: String, name: String)
    suspend fun addToPlaylist(s: Session, playlistId: String, itemIds: List<String>)
    suspend fun removeFromPlaylist(s: Session, playlistId: String, entryIds: List<String>)
    suspend fun moveInPlaylist(s: Session, playlistId: String, entryId: String, newIndex: Int)

    /** Reads the playlist's own `CanDelete`; false refuses (`FORBIDDEN`) with nothing sent. */
    suspend fun deletePlaylist(s: Session, playlistId: String)

    suspend fun collections(s: Session): List<ItemWithUserData>
    suspend fun createCollection(s: Session, name: String, itemIds: List<String>)
    suspend fun addToCollection(s: Session, collectionId: String, itemIds: List<String>)
    suspend fun removeFromCollection(s: Session, collectionId: String, itemIds: List<String>)

    /**
     * The collections that directly hold [itemId] ("Part of this collection"), from a per-profile in-memory index read on first use
     * and kept 10 minutes (see [ListsLimits]). An add or remove made through this seam updates it at once.
     */
    suspend fun collectionsOf(s: Session, itemId: String): List<CollectionRef>
}

/** A playlist: the item itself, whether this profile may delete it, and its entries in order. */
data class Playlist(val item: Item, val canDelete: Boolean, val entries: List<PlaylistEntry>)

/** One place in a playlist. [entryId] is the server's `PlaylistItemId` (`[A-Za-z0-9-]{1,64}`), the key for remove and move. */
data class PlaylistEntry(val entryId: String, val item: ItemWithUserData)

/** A collection by id and name, as the index keeps it. */
data class CollectionRef(val id: String, val name: String)

/** Bounds shared by the gateway, the mappers and the screens. */
object ListsLimits {
    const val NAME = 200
    const val PAGE = 200
    const val ENTRIES = 2_000
    const val LISTED = 500
    const val IDS_PER_CALL = 500
    const val INDEX_COLLECTIONS = 100
    const val INDEX_CHILDREN = 500
    const val INDEX_TTL_MS = 10L * 60 * 1000
}
