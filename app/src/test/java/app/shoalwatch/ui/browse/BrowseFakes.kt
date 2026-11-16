package app.shoalwatch.ui.browse

import app.shoalwatch.core.AppError
import app.shoalwatch.data.library.Freshness
import app.shoalwatch.data.library.Home
import app.shoalwatch.data.library.HomeRow
import app.shoalwatch.data.library.ItemDetail
import app.shoalwatch.data.library.ItemFilter
import app.shoalwatch.data.library.Library
import app.shoalwatch.data.library.ListShape
import app.shoalwatch.data.library.Listing
import app.shoalwatch.data.library.Playstate
import app.shoalwatch.data.library.RefreshTarget
import app.shoalwatch.data.library.SearchResult
import app.shoalwatch.data.library.SeriesDetail
import app.shoalwatch.data.library.Sort
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

val EMPTY_HOME = Home(emptyList(), Freshness.Fresh, HomeRow(emptyList(), Freshness.Fresh), HomeRow(emptyList(), Freshness.Fresh), emptyList())
val EMPTY_LISTING = Listing(emptyList(), null, Freshness.Fresh, canLoadMore = false)

/** A `children` list as the screen asked for it: the whole tuple, so a test can see that every call used the same one. */
data class ChildrenKey(val s: Session, val parentId: String, val shape: ListShape, val sort: Sort, val filter: ItemFilter)

/**
 * A `Library` the tests drive by hand. Every flow is a [MutableStateFlow] per key that the test sets, and every call is
 * recorded, so a test can say exactly what the screen asked for, how many times and as whom.
 */
class FakeLibrary : Library {
    private val homes = mutableMapOf<Session, MutableStateFlow<Home>>()
    private val lists = mutableMapOf<ChildrenKey, MutableStateFlow<Listing>>()
    private val serieses = mutableMapOf<Triple<Session, String, String?>, MutableStateFlow<SeriesDetail>>()
    private val items = mutableMapOf<Pair<Session, String>, MutableStateFlow<ItemDetail>>()

    val homeCollects = mutableListOf<Session>()
    val childrenCollects = mutableListOf<ChildrenKey>()
    val seriesCollects = mutableListOf<Triple<Session, String, String?>>()
    val itemCollects = mutableListOf<Pair<Session, String>>()
    val loadMores = mutableListOf<ChildrenKey>()
    val refreshes = mutableListOf<Pair<Session, RefreshTarget>>()
    val searches = mutableListOf<Pair<Session, String>>()

    /** Set to hold [loadMore] until the test completes it. */
    var loadMoreGate: CompletableDeferred<Unit>? = null
    var onSearch: suspend (Session, String) -> SearchResult = { _, _ -> SearchResult(emptyList(), offline = false) }
    var onRefresh: suspend (Session, RefreshTarget) -> Unit = { _, _ -> }
    var onLoadMore: suspend (ChildrenKey) -> Unit = {}

    /** Set to make every observing flow throw as it starts, as a Room flow does when the database is damaged. */
    var observeFailure: Exception? = null

    fun homeFlow(s: Session) = homes.getOrPut(s) { MutableStateFlow(EMPTY_HOME) }
    fun listFlow(key: ChildrenKey) = lists.getOrPut(key) { MutableStateFlow(EMPTY_LISTING) }
    fun seriesFlow(s: Session, seriesId: String, seasonId: String? = null) =
        serieses.getOrPut(Triple(s, seriesId, seasonId)) { MutableStateFlow(SeriesDetail(null, emptyList(), emptyList(), null, Freshness.Refreshing)) }
    fun itemFlow(s: Session, itemId: String) = items.getOrPut(s to itemId) { MutableStateFlow(ItemDetail(null, Freshness.Refreshing)) }

    override fun home(s: Session): Flow<Home> = flow { observeFailure?.let { throw it }; homeCollects += s; emitAll(homeFlow(s)) }

    override fun children(s: Session, parentId: String, shape: ListShape, sort: Sort, filter: ItemFilter): Flow<Listing> {
        val key = ChildrenKey(s, parentId, shape, sort, filter)
        return flow { observeFailure?.let { throw it }; childrenCollects += key; emitAll(listFlow(key)) }
    }

    override suspend fun loadMore(s: Session, parentId: String, shape: ListShape, sort: Sort, filter: ItemFilter) {
        val key = ChildrenKey(s, parentId, shape, sort, filter)
        loadMores += key
        loadMoreGate?.await()
        onLoadMore(key)
    }

    override fun series(s: Session, seriesId: String, seasonId: String?): Flow<SeriesDetail> =
        flow { observeFailure?.let { throw it }; seriesCollects += Triple(s, seriesId, seasonId); emitAll(seriesFlow(s, seriesId, seasonId)) }

    override fun item(s: Session, itemId: String): Flow<ItemDetail> = flow { observeFailure?.let { throw it }; itemCollects += s to itemId; emitAll(itemFlow(s, itemId)) }

    override suspend fun search(s: Session, term: String): SearchResult {
        searches += s to term
        return onSearch(s, term)
    }

    override suspend fun refresh(s: Session, target: RefreshTarget) {
        refreshes += s to target
        onRefresh(s, target)
    }
}

/** A `Playstate` that records each call, can be held, and can fail with a chosen error. */
class FakePlaystate : Playstate {
    val calls = mutableListOf<String>()
    var failWith: AppError? = null
    var gate: CompletableDeferred<Unit>? = null

    private fun who(s: Session) = when (s) { BEN -> "ben"; AMY -> "amy"; else -> "other" }

    private suspend fun record(call: String) {
        calls += call
        gate?.await()
        failWith?.let { throw it }
    }

    override suspend fun setPlayed(s: Session, itemId: String, played: Boolean) = record("played:${who(s)}:$itemId:$played")
    override suspend fun setFavourite(s: Session, itemId: String, favourite: Boolean) = record("favourite:${who(s)}:$itemId:$favourite")
    override suspend fun removeFromContinueWatching(s: Session, itemId: String) = record("remove:${who(s)}:$itemId")
    override suspend fun recordStop(s: Session, itemId: String, positionMs: Long, finished: Boolean, reached: Boolean) =
        record("stop:${who(s)}:$itemId:$positionMs:$finished" + if (reached) "" else ":unsent")
}
