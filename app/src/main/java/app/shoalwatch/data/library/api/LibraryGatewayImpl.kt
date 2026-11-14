package app.shoalwatch.data.library.api

import app.shoalwatch.core.AppError
import app.shoalwatch.core.displayText
import app.shoalwatch.data.api.ServerApi
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.library.ItemFilter
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.LibraryView
import app.shoalwatch.data.library.Page
import app.shoalwatch.data.library.Sort
import app.shoalwatch.data.servers.JellyfinId
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable

/**
 * [LibraryGateway] on [ServerApi]. Every request names the user explicitly (`userId`), so it is unambiguous whose
 * watched state is wanted. Paths are built only from ids that passed [JellyfinId]; responses go through the mappers
 * in `Mappers.kt` and nothing but models leaves. Endpoint-by-endpoint reference checks are in docs/ARCHITECTURE.md.
 */
class LibraryGatewayImpl(private val api: ServerApi) : LibraryGateway {

    override suspend fun views(s: Session): List<LibraryView> =
        api.get(s, "/UserViews", listOf("userId" to s.userId), BaseItemDtoQueryResult.serializer()).toViews()

    override suspend fun children(
        s: Session,
        parentId: String,
        start: Int,
        limit: Int,
        sort: Sort,
        filter: ItemFilter,
        kinds: Set<ItemKind>,
        recursive: Boolean,
    ): Page<ItemWithUserData> {
        val parent = idOf(parentId)
        val first = start.coerceAtLeast(0)
        val count = limit.coerceIn(1, MAX_PAGE)
        val query = buildList {
            add("userId" to s.userId)
            add("parentId" to parent)
            add("startIndex" to first.toString())
            add("limit" to count.toString())
            add("sortBy" to sort.by.wire)
            add("sortOrder" to sort.order)
            filter.wire().takeIf { it.isNotEmpty() }?.let { add("filters" to it) }
            kinds.wire().takeIf { it.isNotEmpty() }?.let { add("includeItemTypes" to it) }
            add("recursive" to recursive.toString())
            add("fields" to "ChildCount")
            addAll(MEDIA_QUERY)
        }
        return api.get(s, "/Items", query, BaseItemDtoQueryResult.serializer()).toPage(first, count)
    }

    override suspend fun item(s: Session, itemId: String): ItemWithUserData {
        val dto = api.get(s, "/Items/${idOf(itemId)}", listOf("userId" to s.userId), BaseItemDto.serializer())
        // The server answered, but with an item whose own id is not a GUID: it is not safe to hand on.
        return dto.toItemWithUserData() ?: throw AppError.of(AppError.Kind.SERVER_ERROR)
    }

    override suspend fun seasons(s: Session, seriesId: String): List<ItemWithUserData> =
        api.get(s, "/Shows/${idOf(seriesId)}/Seasons", listOf("userId" to s.userId, "fields" to "ChildCount") + MEDIA_QUERY, BaseItemDtoQueryResult.serializer())
            .toModels(Limits.MAX_UNPAGED)

    override suspend fun episodes(s: Session, seriesId: String, seasonId: String?): List<ItemWithUserData> {
        val series = idOf(seriesId)
        val season = seasonId?.let(::idOf)
        val query = buildList {
            add("userId" to s.userId)
            season?.let { add("seasonId" to it) }
            add("fields" to "Overview")
            addAll(MEDIA_QUERY)
        }
        return api.get(s, "/Shows/$series/Episodes", query, BaseItemDtoQueryResult.serializer()).toModels(Limits.MAX_UNPAGED)
    }

    override suspend fun resume(s: Session, limit: Int): List<ItemWithUserData> {
        val count = limit.coerceIn(1, MAX_PAGE)
        val query = listOf("userId" to s.userId, "limit" to count.toString(), "includeItemTypes" to "Movie,Episode") + MEDIA_QUERY
        return api.get(s, "/UserItems/Resume", query, BaseItemDtoQueryResult.serializer()).toModels(count)
    }

    override suspend fun nextUp(s: Session, limit: Int): List<ItemWithUserData> {
        val count = limit.coerceIn(1, MAX_PAGE)
        val query = listOf("userId" to s.userId, "limit" to count.toString(), "enableResumable" to "false", "enableRewatching" to "false") + MEDIA_QUERY
        return api.get(s, "/Shows/NextUp", query, BaseItemDtoQueryResult.serializer()).toModels(count)
    }

    /** The one endpoint here that answers with a bare array rather than a query result. */
    override suspend fun latest(s: Session, viewId: String, limit: Int): List<ItemWithUserData> {
        val count = limit.coerceIn(1, MAX_PAGE)
        val query = listOf("userId" to s.userId, "parentId" to idOf(viewId), "limit" to count.toString()) + MEDIA_QUERY
        return api.get(s, "/Items/Latest", query, ListSerializer(BaseItemDto.serializer().nullable)).toModels(count)
    }

    override suspend fun search(s: Session, term: String, limit: Int): List<ItemWithUserData> {
        val text = displayText(term, MAX_TERM)
        if (text.isEmpty()) return emptyList()
        val count = limit.coerceIn(1, MAX_SEARCH)
        val query = listOf(
            "userId" to s.userId, "searchTerm" to text, "recursive" to "true", "includeItemTypes" to "Movie,Series,Episode", "limit" to count.toString(),
        ) + MEDIA_QUERY
        return api.get(s, "/Items", query, BaseItemDtoQueryResult.serializer()).toModels(count)
    }

    override suspend fun setPlayed(s: Session, itemId: String, played: Boolean) {
        val path = "/UserPlayedItems/${idOf(itemId)}"
        val query = listOf("userId" to s.userId)
        if (played) api.post(s, path, query, retry = true) else api.delete(s, path, query, retry = true)
    }

    override suspend fun setFavourite(s: Session, itemId: String, favourite: Boolean) {
        val path = "/UserFavoriteItems/${idOf(itemId)}"
        val query = listOf("userId" to s.userId)
        if (favourite) api.post(s, path, query, retry = true) else api.delete(s, path, query, retry = true)
    }

    override suspend fun clearResume(s: Session, itemId: String) {
        api.post(s, "/UserItems/${idOf(itemId)}/UserData", listOf("userId" to s.userId), """{"PlaybackPositionTicks":0}""", retry = true)
    }

    /** A caller's id as a normalised GUID; anything else fails before a request is made, without echoing it. */
    private fun idOf(raw: String): String = JellyfinId.parseOrNull(raw)?.value ?: throw AppError.of(AppError.Kind.SERVER_NOT_FOUND)

    private companion object {
        const val MAX_PAGE = 200
        const val MAX_SEARCH = 50
        const val MAX_TERM = 100

        /** What every list asks for besides its own fields: user data and the three image types the app draws, one of each. */
        val MEDIA_QUERY = listOf(
            "enableUserData" to "true",
            "enableImages" to "true",
            "enableImageTypes" to "Primary,Backdrop,Thumb",
            "imageTypeLimit" to "1",
        )
    }
}
