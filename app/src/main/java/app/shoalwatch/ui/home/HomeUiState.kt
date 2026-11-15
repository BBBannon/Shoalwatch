package app.shoalwatch.ui.home

import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.LibraryView
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.ConnectionView
import app.shoalwatch.ui.browse.Work
import app.shoalwatch.ui.present.BannerKind

enum class HomePhase { LOADING, SIGNED_OUT, LOAD_FAILED, READY }

/** One row of cards: the saved items, whether the first load is still running (a skeleton shows), and a banner if this row alone failed. */
data class RowUi(val items: List<ItemWithUserData> = emptyList(), val loading: Boolean = false, val banner: BannerKind? = null)

data class LatestUi(val view: LibraryView, val row: RowUi)

/**
 * Everything Home shows, for one signed-in session ([session] is the one every item and artwork reference below belongs to).
 * Rows with nothing in them are left out by the screen. [topBanner] is set when every failing part failed the same way (one
 * banner, not five); otherwise each failing row carries its own [RowUi.banner].
 */
data class HomeUiState(
    val phase: HomePhase = HomePhase.LOADING,
    val session: Session? = null,
    val serverId: String? = null,
    val serverName: String? = null,
    val userName: String? = null,
    val connection: ConnectionView? = null,
    val accounts: List<AccountSummary> = emptyList(),
    val views: List<LibraryView> = emptyList(),
    val viewsBanner: BannerKind? = null,
    val resume: RowUi = RowUi(),
    val nextUp: RowUi = RowUi(),
    val latest: List<LatestUi> = emptyList(),
    val topBanner: BannerKind? = null,
    /** A refresh is running somewhere (a thin progress line shows). */
    val refreshing: Boolean = false,
    /** The user's own pull-to-refresh or Retry is running. */
    val pulling: Boolean = false,
    val working: Set<Work> = emptySet(),
) {
    val signedOut: Boolean get() = phase == HomePhase.SIGNED_OUT
    val loadFailed: Boolean get() = phase == HomePhase.LOAD_FAILED

    val hasContent: Boolean
        get() = resume.items.isNotEmpty() || nextUp.items.isNotEmpty() || views.isNotEmpty() || latest.any { it.row.items.isNotEmpty() }

    /** Nothing saved yet and a first load is running: skeletons show instead of "empty". */
    val stillLoading: Boolean get() = !hasContent && (refreshing || resume.loading || nextUp.loading)
}
