package app.shoalwatch.data.discover.api

import app.shoalwatch.core.AppError
import app.shoalwatch.core.AppLog
import app.shoalwatch.core.displayText
import app.shoalwatch.data.api.JsonBodies
import app.shoalwatch.data.api.ServerApi
import app.shoalwatch.data.discover.BrowseFilter
import app.shoalwatch.data.discover.Discover
import app.shoalwatch.data.discover.DiscoverLimits
import app.shoalwatch.data.discover.Extras
import app.shoalwatch.data.discover.FilterOptions
import app.shoalwatch.data.discover.Named
import app.shoalwatch.data.discover.SearchExtras
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.ListShape
import app.shoalwatch.data.library.Page
import app.shoalwatch.data.library.Sort
import app.shoalwatch.data.library.api.BaseItemDtoQueryResult
import app.shoalwatch.data.library.api.LibraryGatewayImpl.Companion.MEDIA_QUERY
import app.shoalwatch.data.library.api.toItem
import app.shoalwatch.data.library.api.toItemDto
import app.shoalwatch.data.library.api.toModels
import app.shoalwatch.data.library.api.toPage
import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement

/**
 * [Discover] on [ServerApi], the one authenticated call path. Reads only, so nothing here needs `verifyUnauthorised` (a 401 on a
 * read is handled by ServerApi as for the library). Ids are validated before anything is sent; every list is cut to its bound.
 * Every path and parameter was checked against the 12.4.0 reference. Failures are logged by kind only.
 */
internal class DiscoverGateway(private val api: ServerApi) : Discover {

    override suspend fun extras(s: Session, itemId: String): Extras = logged("extras") {
        val id = normalisedId(itemId)
        val user = listOf("userId" to s.userId)
        // The four reads run together and fail alone. The item's own answer feeds three parts (cast, online trailers, versions).
        val (own, local, features, similar) = coroutineScope {
            val a = async { attempt { api.get(s, "/Items/$id", user, ItemExtrasDto.serializer(), maxBytes = ITEM_BYTES) } }
            val b = async { attempt { api.get(s, "/Items/$id/LocalTrailers", user, ARRAY, maxBytes = LIST_BYTES) } }
            val c = async { attempt { api.get(s, "/Items/$id/SpecialFeatures", user, ARRAY, maxBytes = LIST_BYTES) } }
            val d = async {
                attempt {
                    api.get(s, "/Items/$id/Similar", user + ("limit" to DiscoverLimits.SIMILAR.toString()), BaseItemDtoQueryResult.serializer(), maxBytes = LIST_BYTES)
                }
            }
            Four(a.await(), b.await(), c.await(), d.await())
        }
        val results = listOf(own, local, features, similar)
        if (results.all { it.isFailure }) throw own.exceptionOrNull() as AppError
        results.forEach { r -> r.exceptionOrNull()?.let { AppLog.d(TAG, "discover extras part failed: ${(it as AppError).kind}") } }
        // An item answer that is about some other item is not trusted for anything.
        val item = own.getOrNull()?.takeIf { normalisedIdOrNull(it.id) == id }
        Extras(
            cast = item?.people.toCast(s),
            localTrailers = local.getOrNull().toModels(DiscoverLimits.LOCAL_TRAILERS),
            onlineTrailers = item?.remoteTrailers.toOnlineTrailers(),
            specialFeatures = features.getOrNull().toModels(DiscoverLimits.SPECIAL_FEATURES),
            similar = similar.getOrNull()?.toModels(DiscoverLimits.SIMILAR).orEmpty(),
            versions = item?.mediaSources.toVersions(),
        )
    }

    override suspend fun person(s: Session, personId: String): Item = logged("person") {
        val id = normalisedId(personId)
        val own = api.get(s, "/Items/$id", listOf("userId" to s.userId), JsonElement.serializer(), maxBytes = ITEM_BYTES)
        own.toItemDto()?.toItem()?.takeIf { it.id == id } ?: throw AppError.of(AppError.Kind.SERVER_ERROR)
    }

    override suspend fun personWorks(s: Session, personId: String, startIndex: Int): Page<ItemWithUserData> = logged("personWorks") {
        val id = normalisedId(personId)
        val start = checkedStart(startIndex)
        val query = listOf(
            "userId" to s.userId, "personIds" to id, "recursive" to "true", "includeItemTypes" to "Movie,Series",
            "sortBy" to "SortName", "startIndex" to start.toString(), "limit" to DiscoverLimits.PAGE.toString(), "fields" to "ChildCount",
        ) + MEDIA_QUERY
        api.get(s, "/Items", query, BaseItemDtoQueryResult.serializer(), maxBytes = LIST_BYTES).toPage(start, DiscoverLimits.PAGE)
    }

    override suspend fun browse(s: Session, parentId: String?, shape: ListShape, filter: BrowseFilter, sort: Sort, startIndex: Int): Page<ItemWithUserData> =
        logged("browse") {
            val query = browseQuery(s.userId, parentId, shape, filter, sort, startIndex)
            api.get(s, "/Items", query, BaseItemDtoQueryResult.serializer(), maxBytes = LIST_BYTES).toPage(startIndex, DiscoverLimits.PAGE)
        }

    override suspend fun filterOptions(s: Session, parentId: String): FilterOptions = logged("filterOptions") {
        val id = normalisedId(parentId)
        val scoped = listOf("userId" to s.userId, "parentId" to id)
        val named = scoped + listOf("enableImages" to "false", "enableTotalRecordCount" to "false")
        val (filters, genres, studios) = coroutineScope {
            val a = async { attempt { api.get(s, "/Items/Filters", scoped, FiltersDto.serializer(), maxBytes = LIST_BYTES) } }
            val b = async {
                attempt { api.get(s, "/Genres", named + ("limit" to DiscoverLimits.GENRES.toString()), BaseItemDtoQueryResult.serializer(), maxBytes = LIST_BYTES) }
            }
            val c = async {
                attempt { api.get(s, "/Studios", named + ("limit" to DiscoverLimits.STUDIOS.toString()), BaseItemDtoQueryResult.serializer(), maxBytes = LIST_BYTES) }
            }
            Triple(a.await(), b.await(), c.await())
        }
        if (filters.isFailure && genres.isFailure && studios.isFailure) throw filters.exceptionOrNull() as AppError
        FilterOptions(
            years = filters.getOrNull()?.years.toYears(),
            ratings = filters.getOrNull()?.officialRatings.toFilterTexts(DiscoverLimits.RATING, DiscoverLimits.RATINGS),
            tags = filters.getOrNull()?.tags.toFilterTexts(DiscoverLimits.NAME, DiscoverLimits.TAGS),
            genres = genres.getOrNull()?.items.toNamedList(DiscoverLimits.GENRES),
            studios = studios.getOrNull()?.items.toNamedList(DiscoverLimits.STUDIOS),
        )
    }

    override suspend fun genres(s: Session, parentId: String?, startIndex: Int): Page<Named> = logged("genres") {
        namedPage(s, "/Genres", parentId, startIndex, sorted = true)
    }

    override suspend fun studios(s: Session, parentId: String?, startIndex: Int): Page<Named> = logged("studios") {
        namedPage(s, "/Studios", parentId, startIndex, sorted = false)
    }

    override suspend fun searchMore(s: Session, term: String): SearchExtras = logged("searchMore") {
        val searchTerm = displayText(term, DiscoverLimits.SEARCH_TERM)
        if (searchTerm.isEmpty()) return@logged SearchExtras.NONE
        val limit = DiscoverLimits.SEARCH.toString()
        val base = listOf("userId" to s.userId, "searchTerm" to searchTerm, "limit" to limit)
        val (people, genres, collections) = coroutineScope {
            val a = async {
                val q = base + listOf("enableImages" to "true", "enableImageTypes" to "Primary", "imageTypeLimit" to "1")
                attempt { api.get(s, "/Persons", q, BaseItemDtoQueryResult.serializer(), maxBytes = LIST_BYTES) }
            }
            val b = async {
                val q = base + listOf("enableImages" to "false")
                attempt { api.get(s, "/Genres", q, BaseItemDtoQueryResult.serializer(), maxBytes = LIST_BYTES) }
            }
            val c = async {
                val q = base + listOf("includeItemTypes" to "BoxSet", "recursive" to "true") + MEDIA_QUERY
                attempt { api.get(s, "/Items", q, BaseItemDtoQueryResult.serializer(), maxBytes = LIST_BYTES) }
            }
            Triple(a.await(), b.await(), c.await())
        }
        if (people.isFailure && genres.isFailure && collections.isFailure) throw people.exceptionOrNull() as AppError
        SearchExtras(
            people = people.getOrNull()?.items.toPeople(s, DiscoverLimits.SEARCH),
            genres = genres.getOrNull()?.items.toNamedList(DiscoverLimits.SEARCH),
            collections = collections.getOrNull()?.toModels(DiscoverLimits.SEARCH).orEmpty(),
        )
    }

    // ---- rules

    private suspend fun namedPage(s: Session, path: String, parentId: String?, startIndex: Int, sorted: Boolean): Page<Named> {
        val start = checkedStart(startIndex)
        val query = buildList {
            add("userId" to s.userId)
            parentId?.let { add("parentId" to normalisedId(it)) }
            add("startIndex" to start.toString())
            add("limit" to DiscoverLimits.PAGE.toString())
            if (sorted) add("sortBy" to "SortName")
            add("enableImages" to "false")
        }
        val result = api.get(s, path, query, BaseItemDtoQueryResult.serializer(), maxBytes = LIST_BYTES)
        return result.toPageOf(start, DiscoverLimits.PAGE, { it.toNamed() }, { it.id })
    }

    private fun normalisedIdOrNull(raw: String?): String? = JellyfinId.parseOrNull(raw)?.value

    /** One part of a larger answer: an `AppError` is kept for the caller to weigh, anything else is not ours to hide. */
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: AppError) {
        Result.failure(e)
    }

    private inline fun <T> logged(call: String, block: () -> T): T = try {
        block()
    } catch (e: AppError) {
        AppLog.d(TAG, "discover $call failed: ${e.kind}")
        throw e
    }

    private data class Four<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private companion object {
        const val TAG = "Discover"
        const val ONE_MIB = 1024L * 1024
        const val ITEM_BYTES = 4 * ONE_MIB
        const val LIST_BYTES = 4 * ONE_MIB
        val ARRAY = ListSerializer(JsonElement.serializer())
    }
}
