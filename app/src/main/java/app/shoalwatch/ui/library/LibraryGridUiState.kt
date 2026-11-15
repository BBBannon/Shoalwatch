package app.shoalwatch.ui.library

import app.shoalwatch.data.library.ItemFilter
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.LibraryKind
import app.shoalwatch.data.library.ListShape
import app.shoalwatch.data.library.Sort
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.browse.Work
import app.shoalwatch.ui.present.BannerKind

enum class GridPhase { LOADING, SIGNED_OUT, GONE, READY }

/**
 * A library (or folder, or collection) as a grid. [session] is the one every item and picture reference belongs to; [shape],
 * [sort] and [filter] are the tuple the list was asked for with, and the very same tuple is used to load more and to refresh.
 */
data class LibraryGridUiState(
    val phase: GridPhase = GridPhase.LOADING,
    val session: Session? = null,
    val title: String = "",
    val kind: LibraryKind? = null,
    val shape: ListShape? = null,
    val sort: Sort = DEFAULT_SORT,
    val filter: ItemFilter = ItemFilter(),
    val items: List<ItemWithUserData> = emptyList(),
    val total: Int? = null,
    /** A refresh or a page is on its way: a thin progress line shows, never a blocking spinner once there is content. */
    val refreshing: Boolean = false,
    /** True only when nothing is running and the last attempt did not fail: the moment more can be loaded without a Retry. */
    val fresh: Boolean = false,
    val banner: BannerKind? = null,
    val canLoadMore: Boolean = false,
    val pulling: Boolean = false,
    val working: Set<Work> = emptySet(),
) {
    val filtered: Boolean get() = filter != ItemFilter()

    /** Nothing to show and nothing coming: say so (never while more can be loaded, or something is loading, or it failed). */
    val showEmpty: Boolean get() = phase == GridPhase.READY && items.isEmpty() && !canLoadMore && !refreshing && banner == null

    /** Nothing saved and the last attempt failed: an error state with Retry (the banner carries the button). */
    val showError: Boolean get() = phase == GridPhase.READY && items.isEmpty() && banner != null

    /** First load: skeleton cards rather than an empty grid. */
    val showSkeleton: Boolean get() = phase == GridPhase.LOADING || (items.isEmpty() && (refreshing || canLoadMore) && banner == null)
}
