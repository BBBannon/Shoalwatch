package app.shoalwatch.data.lists.api

import app.shoalwatch.core.AppError
import app.shoalwatch.core.AppLog
import app.shoalwatch.core.MonotonicClock
import app.shoalwatch.data.api.JsonBodies
import app.shoalwatch.data.api.ServerApi
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.api.LibraryGatewayImpl.Companion.MEDIA_QUERY
import app.shoalwatch.data.library.api.BaseItemDtoQueryResult
import app.shoalwatch.data.library.api.toItemDto
import app.shoalwatch.data.library.api.toModels
import app.shoalwatch.data.lists.CollectionRef
import app.shoalwatch.data.lists.Lists
import app.shoalwatch.data.lists.ListsLimits
import app.shoalwatch.data.lists.Playlist
import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonElement

/**
 * [Lists] on [ServerApi], the one authenticated call path. Reads use ServerApi's one retry; every change is sent once
 * (`retry = false`, `sendOnce`) with `verifyUnauthorised = true`, so a refusal answered 401 is checked with `GET /Users/Me` before
 * anything signs out (plan decision 18). Ids are validated before anything is sent. Every path, parameter and field was checked
 * against the 12.4.0 reference. Failures are logged by kind only.
 */
internal class ListsGateway(private val api: ServerApi, clock: MonotonicClock) : Lists {
    private val index = CollectionIndex(clock)

    override suspend fun playlists(s: Session): List<ItemWithUserData> = logged("playlists") {
        val query = listOf(
            "userId" to s.userId, "includeItemTypes" to "Playlist", "recursive" to "true", "sortBy" to "SortName", "fields" to "ChildCount",
        ) + MEDIA_QUERY
        api.get(s, "/Items", query, BaseItemDtoQueryResult.serializer(), maxBytes = LIST_BYTES).toModels(ListsLimits.LISTED)
    }

    override suspend fun playlist(s: Session, playlistId: String): Playlist = logged("playlist") {
        val id = itemId(playlistId)
        val own = api.get(s, "/Items/$id", listOf("userId" to s.userId), JsonElement.serializer(), maxBytes = ONE_MIB)
        val entries = ArrayList<app.shoalwatch.data.lists.PlaylistEntry>()
        val seen = HashSet<String>()
        var start = 0
        // At most ENTRIES elements are read from the server, a page at a time; a short or empty page is the end.
        while (start < ListsLimits.ENTRIES) {
            val query = listOf("userId" to s.userId, "startIndex" to start.toString(), "limit" to ListsLimits.PAGE.toString()) +
                MEDIA_QUERY
            val page = api.get(s, "/Playlists/$id/Items", query, BaseItemDtoQueryResult.serializer(), maxBytes = LIST_BYTES)
            val raw = page.items.orEmpty().size
            entries += page.items.toEntries(ListsLimits.ENTRIES - entries.size, seen)
            start += raw
            if (raw < ListsLimits.PAGE) break
        }
        own.toPlaylist(id, entries) ?: throw AppError.of(AppError.Kind.SERVER_ERROR)
    }

    override suspend fun createPlaylist(s: Session, name: String, itemIds: List<String>) = logged("createPlaylist") {
        val body = NewPlaylistBody(cleaned(name), itemIds(itemIds, allowEmpty = true), s.userId, isPublic = false)
        send(s, "POST", "/Playlists", jsonBody = JsonBodies.JSON.encodeToString(NewPlaylistBody.serializer(), body))
    }

    override suspend fun renamePlaylist(s: Session, playlistId: String, name: String) = logged("renamePlaylist") {
        val id = itemId(playlistId)
        val body = RenamePlaylistBody(cleaned(name))
        send(s, "POST", "/Playlists/$id", jsonBody = JsonBodies.JSON.encodeToString(RenamePlaylistBody.serializer(), body))
    }

    override suspend fun addToPlaylist(s: Session, playlistId: String, itemIds: List<String>) = logged("addToPlaylist") {
        val id = itemId(playlistId)
        send(s, "POST", "/Playlists/$id/Items", listOf("ids" to itemIds(itemIds).joinToString(","), "userId" to s.userId))
    }

    override suspend fun removeFromPlaylist(s: Session, playlistId: String, entryIds: List<String>) = logged("removeFromPlaylist") {
        val id = itemId(playlistId)
        if (entryIds.size !in 1..ListsLimits.IDS_PER_CALL) throw invalid()
        val entries = entryIds.map { entryId(it) ?: throw AppError.of(AppError.Kind.SERVER_NOT_FOUND) }
        send(s, "DELETE", "/Playlists/$id/Items", listOf("entryIds" to entries.joinToString(",")))
    }

    override suspend fun moveInPlaylist(s: Session, playlistId: String, entryId: String, newIndex: Int) = logged("moveInPlaylist") {
        val id = itemId(playlistId)
        val entry = entryId(entryId) ?: throw AppError.of(AppError.Kind.SERVER_NOT_FOUND)
        if (newIndex !in 0 until ListsLimits.ENTRIES) throw invalid()
        send(s, "POST", "/Playlists/$id/Items/$entry/Move/$newIndex")
    }

    override suspend fun deletePlaylist(s: Session, playlistId: String) = logged("deletePlaylist") {
        val id = itemId(playlistId)
        // The playlist's own answer for this user, read just before sending: Jellyfin answers a refused delete with 401, which is not
        // to be mistaken for this sign-in ending. False (or not said): nothing is sent.
        val own = api.get(s, "/Items/$id", listOf("userId" to s.userId), JsonElement.serializer(), maxBytes = ONE_MIB)
        if (!canDeleteOf(own, id)) throw AppError.of(AppError.Kind.FORBIDDEN)
        api.delete(s, "/Items/$id", retry = false, sendOnce = true, verifyUnauthorised = true)
        index.forget(s)
    }

    override suspend fun collections(s: Session): List<ItemWithUserData> = logged("collections") {
        listCollections(s).toModels(ListsLimits.LISTED)
    }

    override suspend fun createCollection(s: Session, name: String, itemIds: List<String>) = logged("createCollection") {
        val query = listOf("name" to cleaned(name), "ids" to itemIds(itemIds, allowEmpty = true).joinToString(","), "isLocked" to "false")
        send(s, "POST", "/Collections", query)
        index.forget(s)
    }

    override suspend fun addToCollection(s: Session, collectionId: String, itemIds: List<String>) = logged("addToCollection") {
        val id = itemId(collectionId)
        val ids = itemIds(itemIds)
        send(s, "POST", "/Collections/$id/Items", listOf("ids" to ids.joinToString(",")))
        index.added(s, id, ids)
    }

    override suspend fun removeFromCollection(s: Session, collectionId: String, itemIds: List<String>) = logged("removeFromCollection") {
        val id = itemId(collectionId)
        val ids = itemIds(itemIds)
        send(s, "DELETE", "/Collections/$id/Items", listOf("ids" to ids.joinToString(",")))
        index.removed(s, id, ids)
    }

    override suspend fun collectionsOf(s: Session, itemId: String): List<CollectionRef> = logged("collectionsOf") {
        val id = itemId(itemId)
        index.of(s, id) { loadIndex(s) }
    }

    // ---- rules

    private suspend fun listCollections(s: Session): BaseItemDtoQueryResult {
        val query = listOf("userId" to s.userId, "includeItemTypes" to "BoxSet", "recursive" to "true", "sortBy" to "SortName") +
            MEDIA_QUERY
        return api.get(s, "/Items", query, BaseItemDtoQueryResult.serializer(), maxBytes = LIST_BYTES)
    }

    /**
     * Every collection (at most 100) with its direct children (at most 500 each): ids only, no fields, no images. The children are
     * read [ListsLimits.INDEX_READS_AT_ONCE] collections at a time (final review I1), and kept in the order the collections were listed.
     */
    private suspend fun loadIndex(s: Session): List<HeldCollection> = coroutineScope {
        val collections = listCollections(s).toModels(ListsLimits.INDEX_COLLECTIONS)
        val reads = Semaphore(ListsLimits.INDEX_READS_AT_ONCE)
        collections.map { c -> async { reads.withPermit { HeldCollection(c.item.id, c.item.name, childrenOf(s, c.item.id)) } } }.awaitAll()
    }

    private suspend fun childrenOf(s: Session, collectionId: String): MutableSet<String> {
        val query = listOf(
            "userId" to s.userId, "parentId" to collectionId, "limit" to ListsLimits.INDEX_CHILDREN.toString(),
            "enableImages" to "false", "enableUserData" to "false",
        )
        val page = api.get(s, "/Items", query, BaseItemDtoQueryResult.serializer(), maxBytes = LIST_BYTES)
        val children = LinkedHashSet<String>()
        for (element in page.items.orEmpty()) {
            if (children.size >= ListsLimits.INDEX_CHILDREN) break
            JellyfinId.parseOrNull(element.toItemDto()?.id)?.let { children += it.value }
        }
        return children
    }

    /** Every change: sent once and never retried, and a 401 is checked before it can sign anyone out. */
    private suspend fun send(s: Session, method: String, path: String, query: List<Pair<String, String>> = emptyList(), jsonBody: String? = null) {
        when (method) {
            "POST" -> api.post(s, path, query, jsonBody, retry = false, sendOnce = true, verifyUnauthorised = true)
            "DELETE" -> api.delete(s, path, query, retry = false, sendOnce = true, verifyUnauthorised = true)
            else -> throw IllegalArgumentException("Not an action method.")
        }
    }

    private inline fun <T> logged(call: String, block: () -> T): T = try {
        block()
    } catch (e: AppError) {
        AppLog.d(TAG, "lists $call failed: ${e.kind}")
        throw e
    }

    /** Only an id already in the normalised form the library uses; a dashed or upper-case one is refused, not repaired. */
    private fun itemId(raw: String) = raw.takeIf { JellyfinId.parseOrNull(it)?.value == it } ?: throw AppError.of(AppError.Kind.SERVER_NOT_FOUND)

    private fun itemIds(raw: List<String>, allowEmpty: Boolean = false): List<String> {
        if (raw.size > ListsLimits.IDS_PER_CALL || (!allowEmpty && raw.isEmpty())) throw invalid()
        return raw.map(::itemId)
    }

    /** Input the seam refuses with nothing sent: an `AppError` so a screen can show it (its wording is the screen's own). */
    private fun invalid() = AppError(AppError.Kind.UNKNOWN, "That name or selection can't be used.")

    private fun cleaned(name: String) = cleanName(name) ?: throw invalid()

    private companion object {
        const val TAG = "Lists"
        const val ONE_MIB = 1024L * 1024
        const val LIST_BYTES = 4 * ONE_MIB
    }
}
