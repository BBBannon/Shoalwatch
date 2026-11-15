package app.shoalwatch.ui.downloads

import androidx.annotation.StringRes
import app.shoalwatch.data.downloads.OfflineLibrary
import app.shoalwatch.data.downloads.OfflineSeries
import app.shoalwatch.data.downloads.QueueStatus
import app.shoalwatch.data.servers.Session

/**
 * The Downloads tab, for the active [session] only (empty and [loading] until that session's first answer). [localFailure]:
 * reading the downloads failed on this phone (Try again). [confirmRemove]: the item whose "Delete download" waits for a yes.
 */
data class DownloadsUiState(
    val session: Session? = null,
    val library: OfflineLibrary = EMPTY_LIBRARY,
    val queue: QueueStatus = QueueStatus.Idle,
    val online: Boolean = false,
    val loading: Boolean = true,
    val localFailure: Boolean = false,
    val confirmRemove: String? = null,
) {
    val empty: Boolean get() = !loading && !localFailure && library.inProgress.isEmpty() && library.films.isEmpty() && library.series.isEmpty()

    companion object {
        val EMPTY_LIBRARY = OfflineLibrary(emptyList(), emptyList(), emptyList())
    }
}

/**
 * A message for the snackbar, which belongs to the session that caused it: the screen drops it once another session is on screen.
 * Either our own words ([res]) or an `AppError.userMessage` as it is ([text]).
 */
data class SessionMessage(val session: Session, @StringRes val res: Int = 0, val text: String? = null) {
    override fun toString() = "SessionMessage(<redacted>)"
}

/** One downloaded series, for the active [session]: series header, then each season (Specials last) with its episodes in order. */
data class OfflineSeriesUiState(
    val session: Session? = null,
    val series: OfflineSeries? = null,
    val loading: Boolean = true,
) {
    /** The id was not a downloaded series this profile may see (or was malformed): "This is no longer available". */
    val gone: Boolean get() = !loading && (series == null || (series.series == null && series.seasons.isEmpty()))
}

/** What the Downloads screens can do; the route turns them into navigation and ViewModel calls. */
data class DownloadsActions(
    val onOpenItem: (String) -> Unit,
    val onOpenSeries: (String) -> Unit,
    val onSignIn: (String) -> Unit,
    val onPause: (String) -> Unit,
    val onResume: (String) -> Unit,
    val onRetry: (String) -> Unit,
    val onAskRemove: (String) -> Unit,
    val onConfirmRemove: () -> Unit,
    val onCancelRemove: () -> Unit,
    val onPauseAll: () -> Unit,
    val onResumeAll: () -> Unit,
    val onTryAgain: () -> Unit,
)
