package app.shoalwatch.data.library

import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.db.library.LibraryStore
import app.shoalwatch.data.db.library.ListStateEntity
import app.shoalwatch.data.db.library.toModel
import app.shoalwatch.data.library.api.LibraryGateway
import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import java.time.Duration

/**
 * [Library] on [LibraryGateway] and Room: screens observe what is saved, the network only writes (see "Library repository"
 * in docs/ARCHITECTURE.md). Refreshes run in [scope] (the app scope), not in a collector's; [io] is where all Room and
 * network work runs, so flows are safe to collect on the main thread. [clock] is epoch milliseconds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepository(
    private val gateway: LibraryGateway,
    db: AppDatabase,
    scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    maintenanceLog: MaintenanceLog,
) : Library {
    private val lists = db.listDao()
    private val items = db.itemDao()
    private val views = db.viewDao()
    private val refresher = Refresher(scope, io)
    private val cache: ListCache

    init {
        val store = LibraryStore(db)
        val gate = Mutex()
        cache = ListCache(db, store, refresher, LibraryMaintenance(store, maintenanceLog, clock, scope, io, gate), clock, io, gate)
    }

    // ---- recipes: what to fetch and save for each list, and how long a saved copy stays current

    private fun viewsRecipe(s: Session) = Recipe(ListKey.VIEWS, HOME_WINDOW) {
        cache.replaceViews(s, gateway.views(s))
        cache.markLoaded(s, ListKey.VIEWS)
    }

    private fun wholeList(s: Session, key: String, window: Duration, fetch: suspend () -> List<ItemWithUserData>) =
        Recipe(key, window) { cache.writeWhole(s, key, fetch()) }

    private fun resumeRecipe(s: Session) = wholeList(s, ListKey.RESUME, HOME_WINDOW) { gateway.resume(s, RESUME_LIMIT) }

    private fun nextUpRecipe(s: Session) = wholeList(s, ListKey.NEXT_UP, HOME_WINDOW) { gateway.nextUp(s, NEXT_UP_LIMIT) }

    private fun latestRecipe(s: Session, viewId: String) = wholeList(s, ListKey.latest(viewId), HOME_WINDOW) { gateway.latest(s, viewId, LATEST_LIMIT) }

    private fun seasonsRecipe(s: Session, seriesId: String) = wholeList(s, ListKey.seasons(seriesId), SERIES_WINDOW) { gateway.seasons(s, seriesId) }

    private fun episodesRecipe(s: Session, seriesId: String, seasonId: String?) =
        wholeList(s, ListKey.episodes(seriesId, seasonId), SERIES_WINDOW) { gateway.episodes(s, seriesId, seasonId) }

    private fun itemRecipe(s: Session, itemId: String, window: Duration) = Recipe(ListKey.item(itemId), window) {
        cache.writeItems(s, listOf(gateway.item(s, itemId)), authoritative = true) // a detail fetch asks for every field, so a missing overview is really gone
        cache.markLoaded(s, ListKey.item(itemId))
    }

    /** Page 0 replaces the whole list, so anything the server dropped disappears; pages after the first are fetched again by [loadMore]. */
    private fun childrenRecipe(s: Session, parentId: String, shape: ListShape, sort: Sort, filter: ItemFilter): Recipe {
        val key = ListKey.children(parentId, shape, sort, filter)
        return Recipe(key, LISTING_WINDOW) {
            val page = gateway.children(s, parentId, 0, PAGE_SIZE, sort, filter, shape.kinds, shape.recursive)
            cache.writePage(s, key, 0, page.items, page.total, page.nextStartIndex, 1, page.endReached)
        }
    }

    // ---- home

    override fun home(s: Session): Flow<Home> {
        val libraries = flow {
            cache.ensure(s, viewsRecipe(s))
            emitAll(views.observeViews(s.serverId, s.userId).map { rows -> rows.map { it.toModel() } })
        }.distinctUntilChanged()
        return libraries.flatMapLatest { libs ->
            val rows = listOf(resumeRecipe(s), nextUpRecipe(s)) + libs.map { latestRecipe(s, it.id) }
            combine(rows.map { recipe -> cache.rows(s, recipe, MAX_ROWS).map { HomeRow(it.rows, it.freshness) } }) { all ->
                Home(libs, all[0], all[1], libs.mapIndexed { i, lib -> lib to all[i + 2] })
            }
        }.flowOn(io)
    }

    // ---- listings

    override fun children(s: Session, parentId: String, shape: ListShape, sort: Sort, filter: ItemFilter): Flow<Listing> =
        if (!wellFormed(parentId)) flowOf(Listing(emptyList(), null, NOT_FOUND, false))
        else cache.rows(s, childrenRecipe(s, parentId, shape, sort, filter), MAX_ROWS)
            .map { Listing(it.rows, it.state?.total, it.freshness, canLoadMore(it.state)) }

    /**
     * Ids reach the seam from navigation arguments and saved state, so a malformed one is data, not a programming error: it
     * reads as "not on the server" (flows) or does nothing (suspend functions), and no request or write is made.
     */
    private fun wellFormed(vararg ids: String?) = ids.all { it == null || JellyfinId.parseOrNull(it) != null }

    private fun canLoadMore(state: ListStateEntity?) = state != null && !state.endReached && state.pagesLoaded < MAX_PAGES

    override suspend fun loadMore(s: Session, parentId: String, shape: ListShape, sort: Sort, filter: ItemFilter) {
        if (!wellFormed(parentId)) return
        val key = ListKey.children(parentId, shape, sort, filter)
        if (!canLoadMore(lists.getState(s.serverId, s.userId, key))) return
        refresher.launch(ListId(s, key), Refresher.Mode.MORE) { loadNextPage(s, key, parentId, shape, sort, filter) }?.join()
    }

    /**
     * Continues from the server index the last page stopped at (not from how many rows are stored: the gateway drops
     * unusable elements), keeps the first position of an id already in the list, and stops only on `endReached`.
     */
    private suspend fun loadNextPage(s: Session, key: String, parentId: String, shape: ListShape, sort: Sort, filter: ItemFilter) {
        val state = lists.getState(s.serverId, s.userId, key)?.takeIf { canLoadMore(it) } ?: return
        // Read before fetching: if a refresh replaces the list while this page is on its way, the page is for the old list and is dropped.
        val stored = lists.entryIds(s.serverId, s.userId, key)
        val page = gateway.children(s, parentId, state.nextStartIndex, PAGE_SIZE, sort, filter, shape.kinds, shape.recursive)
        val have = stored.toHashSet()
        val added = page.items.filter { have.add(it.item.id) }
        cache.writePage(s, key, stored.size, added, page.total ?: state.total, page.nextStartIndex, state.pagesLoaded + 1, page.endReached, basedOn = state)
    }

    // ---- series and items

    override fun series(s: Session, seriesId: String, seasonId: String?): Flow<SeriesDetail> {
        if (!wellFormed(seriesId, seasonId)) return flowOf(SeriesDetail(null, emptyList(), emptyList(), null, NOT_FOUND))
        val header = combine(items.observeItem(s.serverId, s.userId, seriesId), cache.status(s, itemRecipe(s, seriesId, SERIES_WINDOW))) { row, f ->
            row?.toModel() to f
        }
        val seasons = cache.rows(s, seasonsRecipe(s, seriesId), MAX_ROWS)
        val saved = items.observeEpisodes(s.serverId, s.userId, seriesId, null).map { rows -> rows.map { it.toModel() } }
        // Sticky: once a season is chosen it stays while it exists, so marking an episode watched never jumps seasons.
        val selected = combine(seasons.map { it.rows }.distinctUntilChanged(), saved) { all, episodes -> all to episodes }
            .runningFold<Pair<List<ItemWithUserData>, List<ItemWithUserData>>, String?>(null) { previous, (all, episodes) ->
                seasonId ?: previous?.takeIf { p -> all.any { it.item.id == p } } ?: selectSeason(all, episodes)
            }.drop(1).distinctUntilChanged()
        val chosen = selected.flatMapLatest { id ->
            if (id == null) flowOf(null to null) else cache.rows(s, episodesRecipe(s, seriesId, id), MAX_ROWS).map { id to it }
        }
        return combine(header, seasons, chosen) { (series, seriesFreshness), seasonList, (id, episodes) ->
            val parts = listOfNotNull(seriesFreshness, seasonList.freshness, episodes?.freshness)
            SeriesDetail(series, seasonList.rows, episodes?.rows.orEmpty(), id, combined(parts))
        }.flowOn(io)
    }

    /** Refreshing if any part is; else the first failure; else fresh. */
    private fun combined(parts: List<Freshness>): Freshness =
        parts.firstOrNull { it is Freshness.Refreshing } ?: parts.firstOrNull { it is Freshness.Stale } ?: Freshness.Fresh

    override fun item(s: Session, itemId: String): Flow<ItemDetail> =
        if (!wellFormed(itemId)) flowOf(ItemDetail(null, NOT_FOUND)) else combine(items.observeItem(s.serverId, s.userId, itemId), cache.status(s, itemRecipe(s, itemId, ITEM_WINDOW))) { row, f ->
            ItemDetail(row?.toModel(), f)
        }.flowOn(io)

    // ---- search

    override suspend fun search(s: Session, term: String): SearchResult {
        val text = term.trim()
        if (text.isEmpty()) return SearchResult(emptyList(), offline = false)
        try {
            val found = gateway.search(s, text, SEARCH_LIMIT)
            // Saved so a detail screen opens at once. A failure to save must not lose the results the user is waiting for.
            try {
                cache.writeItems(s, found)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
            return SearchResult(found, offline = false)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val error = AppError.from(e)
            if (error.kind !in OFFLINE_KINDS) throw error
            val saved = items.searchByName(s.serverId, s.userId, text, SEARCH_KINDS, SEARCH_LIMIT)
            return SearchResult(saved.map { it.toModel() }, offline = true)
        }
    }

    // ---- pull to refresh

    override suspend fun refresh(s: Session, target: RefreshTarget) {
        val ids = when (target) {
            RefreshTarget.Home -> emptyList()
            is RefreshTarget.Children -> listOf(target.parentId)
            is RefreshTarget.Series -> listOf(target.seriesId, target.seasonId)
            is RefreshTarget.Item -> listOf(target.itemId)
        }
        if (!wellFormed(*ids.toTypedArray())) return
        when (target) {
            RefreshTarget.Home -> refreshHome(s)
            is RefreshTarget.Children -> cache.refreshNow(s, childrenRecipe(s, target.parentId, target.shape, target.sort, target.filter))
            is RefreshTarget.Series -> refreshSeries(s, target)
            is RefreshTarget.Item -> cache.refreshNow(s, itemRecipe(s, target.itemId, ITEM_WINDOW))
        }
    }

    private suspend fun refreshHome(s: Session) {
        cache.refreshNow(s, viewsRecipe(s))
        val libs = views.getViews(s.serverId, s.userId).map { it.toModel() }
        refreshTogether(listOf(resumeRecipe(s), nextUpRecipe(s)) + libs.map { latestRecipe(s, it.id) }, s)
    }

    private suspend fun refreshSeries(s: Session, target: RefreshTarget.Series) {
        refreshTogether(listOf(itemRecipe(s, target.seriesId, SERIES_WINDOW), seasonsRecipe(s, target.seriesId)), s)
        val seasons = lists.getList(s.serverId, s.userId, ListKey.seasons(target.seriesId), MAX_ROWS).map { it.toModel() }
        val saved = items.getEpisodes(s.serverId, s.userId, target.seriesId, null).map { it.toModel() }
        (target.seasonId ?: selectSeason(seasons, saved))?.let { cache.refreshNow(s, episodesRecipe(s, target.seriesId, it)) }
    }

    /** Each starts at once and joins any refresh already running; the refresher's own limit keeps the server load gentle. */
    private suspend fun refreshTogether(recipes: List<Recipe>, s: Session) = coroutineScope {
        recipes.forEach { launch { cache.refreshNow(s, it) } }
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 200
        const val MAX_ROWS = PAGE_SIZE * MAX_PAGES
        const val RESUME_LIMIT = 24
        const val NEXT_UP_LIMIT = 24
        const val LATEST_LIMIT = 16
        const val SEARCH_LIMIT = 50
        val HOME_WINDOW: Duration = Duration.ofMinutes(2)
        val LISTING_WINDOW: Duration = Duration.ofMinutes(15)
        val SERIES_WINDOW: Duration = Duration.ofMinutes(10)
        val ITEM_WINDOW: Duration = Duration.ofMinutes(5)
        val NOT_FOUND = Freshness.Stale(AppError.Kind.SERVER_NOT_FOUND)
        val OFFLINE_KINDS = setOf(AppError.Kind.OFFLINE, AppError.Kind.NETWORK, AppError.Kind.TIMEOUT)
        val SEARCH_KINDS = setOf(ItemKind.MOVIE, ItemKind.SERIES, ItemKind.EPISODE)
    }
}
