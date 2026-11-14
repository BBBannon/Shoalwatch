package app.shoalwatch.data.library.api

import app.shoalwatch.core.AppError
import app.shoalwatch.core.displayText
import app.shoalwatch.data.api.ServerApi
import app.shoalwatch.data.library.ItemFilter
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.LibraryView
import app.shoalwatch.data.library.Page
import app.shoalwatch.data.library.Sort
import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.data.servers.Session
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement

/**
 * [LibraryGateway] on [ServerApi]. Every request names the user explicitly (`userId`), so it is unambiguous whose
 * watched state is wanted. Paths are built only from ids that passed [JellyfinId]; responses go through the mappers
 * in `Mappers.kt` and nothing but models leaves. Endpoint-by-endpoint reference checks are in docs/ARCHITECTURE.md.
 */
class LibraryGatewayImpl(private val api: ServerApi) : LibraryGateway {

    override suspend fun views(s: Session): List<LibraryView> = list(s, "/UserViews").toViews()

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
        val first = start.coerceAtLeast(0)
        val count = pageSize(limit)
        val query = buildList {
            add("parentId" to idOf(parentId))
            add("startIndex" to first.toString())
            add("limit" to count.toString())
            add("sortBy" to sort.sortByWire())
            add("sortOrder" to sort.sortOrderWire())
            filter.wire().takeIf { it.isNotEmpty() }?.let { add("filters" to it) }
            kinds.wire().takeIf { it.isNotEmpty() }?.let { add("includeItemTypes" to it) }
            add("recursive" to recursive.toString())
            add("fields" to "ChildCount")
            addAll(MEDIA_QUERY)
        }
        return list(s, "/Items", query).toPage(first, count)
    }

    override suspend fun item(s: Session, itemId: String): ItemWithUserData {
        val dto = api.get(s, "/Items/${idOf(itemId)}", withUser(s), BaseItemDto.serializer())
        // The server answered, but with an item whose own id is not a GUID: it is not safe to hand on.
        return dto.toItemWithUserData() ?: throw AppError.of(AppError.Kind.SERVER_ERROR)
    }

    override suspend fun seasons(s: Session, seriesId: String): List<ItemWithUserData> =
        list(s, "/Shows/${idOf(seriesId)}/Seasons", listOf("fields" to "ChildCount") + MEDIA_QUERY)
            .toModels(Limits.MAX_SEASONS).inSeasonOrder()

    /**
     * Pages through `startIndex`/`limit` until the server's own count is reached, a page is empty, or (when it gave no
     * count) a page comes back short; never more than [MAX_EPISODES] items or [MAX_REQUESTS] requests. The result is
     * sorted here, so it does not depend on the server's order. (The reference's `sortBy` for episodes takes one value
     * only, which cannot express season-then-episode, so none is sent: the server's default is aired order.)
     */
    override suspend fun episodes(s: Session, seriesId: String, seasonId: String?): List<ItemWithUserData> {
        val path = "/Shows/${idOf(seriesId)}/Episodes"
        val season = seasonId?.let(::idOf)
        val found = ArrayList<ItemWithUserData>()
        var start = 0
        var requests = 0
        while (requests < MAX_REQUESTS && start < MAX_EPISODES) {
            val query = buildList {
                season?.let { add("seasonId" to it) }
                add("startIndex" to start.toString())
                add("limit" to EPISODE_PAGE.toString())
                add("fields" to "Overview")
                addAll(MEDIA_QUERY)
            }
            val result = list(s, path, query)
            requests++
            val raw = result.rawCount()
            val total = result.usableTotal(start)
            found += result.toModels(Int.MAX_VALUE)
            start += raw
            if (raw == 0 || (total != null && start >= total) || (total == null && raw < EPISODE_PAGE)) break
        }
        return found.distinctBy { it.item.id }.inEpisodeOrder().take(MAX_EPISODES)
    }

    override suspend fun resume(s: Session, limit: Int): List<ItemWithUserData> {
        val count = pageSize(limit)
        return list(s, "/UserItems/Resume", listOf("limit" to count.toString(), "includeItemTypes" to "Movie,Episode") + MEDIA_QUERY).toModels(count)
    }

    override suspend fun nextUp(s: Session, limit: Int): List<ItemWithUserData> {
        val count = pageSize(limit)
        val query = listOf("limit" to count.toString(), "enableResumable" to "false", "enableRewatching" to "false") + MEDIA_QUERY
        return list(s, "/Shows/NextUp", query).toModels(count)
    }

    /** The one endpoint here that answers with a bare array rather than a query result. */
    override suspend fun latest(s: Session, viewId: String, limit: Int): List<ItemWithUserData> {
        val count = pageSize(limit)
        val query = listOf("parentId" to idOf(viewId), "limit" to count.toString()) + MEDIA_QUERY
        return api.get(s, "/Items/Latest", withUser(s, query), ListSerializer(JsonElement.serializer())).toModels(count)
    }

    override suspend fun search(s: Session, term: String, limit: Int): List<ItemWithUserData> {
        val text = displayText(term, MAX_TERM)
        if (text.isEmpty()) return emptyList()
        val count = pageSize(limit, MAX_SEARCH)
        val query = listOf(
            "searchTerm" to text, "recursive" to "true", "includeItemTypes" to "Movie,Series,Episode", "limit" to count.toString(),
        ) + MEDIA_QUERY
        return list(s, "/Items", query).toModels(count)
    }

    override suspend fun setPlayed(s: Session, itemId: String, played: Boolean) = toggle(s, "/UserPlayedItems/${idOf(itemId)}", played)

    override suspend fun setFavourite(s: Session, itemId: String, favourite: Boolean) = toggle(s, "/UserFavoriteItems/${idOf(itemId)}", favourite)

    override suspend fun clearResume(s: Session, itemId: String) {
        api.post(s, "/UserItems/${idOf(itemId)}/UserData", withUser(s), """{"PlaybackPositionTicks":0}""", retry = true)
    }

    // ---- the shared parts of the twelve operations

    private fun withUser(s: Session, query: List<Pair<String, String>> = emptyList()) = listOf("userId" to s.userId) + query

    /** A GET of a query result for this user. */
    private suspend fun list(s: Session, path: String, query: List<Pair<String, String>> = emptyList()): BaseItemDtoQueryResult =
        api.get(s, path, withUser(s, query), BaseItemDtoQueryResult.serializer())

    /** POST turns it on, DELETE turns it off. Idempotent, so opted in to the one network retry. */
    private suspend fun toggle(s: Session, path: String, on: Boolean) {
        if (on) api.post(s, path, withUser(s), retry = true) else api.delete(s, path, withUser(s), retry = true)
    }

    private fun pageSize(limit: Int, max: Int = MAX_PAGE) = limit.coerceIn(1, max)

    /** A caller's id as a normalised GUID; anything else fails before a request is made, without echoing it. */
    private fun idOf(raw: String): String = JellyfinId.parseOrNull(raw)?.value ?: throw AppError.of(AppError.Kind.SERVER_NOT_FOUND)

    private companion object {
        const val MAX_PAGE = 200
        const val MAX_SEARCH = 50
        const val MAX_TERM = 100
        const val EPISODE_PAGE = 500
        const val MAX_EPISODES = 10_000
        const val MAX_REQUESTS = 50

        /** What every list asks for besides its own fields: user data and the three image types the app draws, one of each. */
        val MEDIA_QUERY = listOf(
            "enableUserData" to "true",
            "enableImages" to "true",
            "enableImageTypes" to "Primary,Backdrop,Thumb",
            "imageTypeLimit" to "1",
        )
    }
}
