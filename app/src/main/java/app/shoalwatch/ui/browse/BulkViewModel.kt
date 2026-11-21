package app.shoalwatch.ui.browse

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.R
import app.shoalwatch.core.AppError
import app.shoalwatch.data.admin.AdminRights
import app.shoalwatch.data.admin.AdminState
import app.shoalwatch.data.admin.isAdminFor
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.DownloadTarget
import app.shoalwatch.data.downloads.Downloads
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.Library
import app.shoalwatch.data.library.Playstate
import app.shoalwatch.data.manage.Manage
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A delete waiting for a yes: [items] by name, each one the server said this profile may delete; [downloaded]: for each item with
 * downloads on this phone, their ids (a film its own; a series or season its episodes). The dialog then offers to keep them.
 */
data class DeleteAsk(val session: Session, val items: List<BulkItem>, val downloaded: Map<String, List<String>>, val keepCopy: Boolean = false) {
    /** How many downloads the delete would take with it. */
    val savedCount: Int get() = downloaded.values.sumOf { it.size }

    override fun toString() = "DeleteAsk(${items.size})"
}

data class BulkUiState(
    val session: Session? = null,
    /** An administrator: Manage is offered for one item. */
    val canManage: Boolean = false,
    /** The profile's policy allows deleting: Delete from server is offered (each item's own `CanDelete` decides). */
    val canDelete: Boolean = false,
    val running: Boolean = false,
    val deleteAsk: DeleteAsk? = null,
) {
    override fun toString() = "BulkUiState(<redacted>)"
}

/** One snackbar line for a whole bulk action: a plural with the count, or plain words. Shown only to its own session. */
data class BulkNotice(val session: Session, @PluralsRes val plural: Int = 0, val count: Int = 0, @StringRes val text: Int = 0) {
    override fun toString() = "BulkNotice(<redacted>)"
}

/**
 * The bar's actions for one screen (Milestone 10, plan decisions 2 to 6). Watched and favourite go through [Playstate] one item at
 * a time (saved on the phone and queued, so they work offline); Download prepares each at the default quality (Original if none)
 * and starts what fits; Delete from server asks first ([askDelete] then [confirmDelete]) and runs as the session that asked. One
 * action at a time; a result is shown only while its session is still on screen. Who may manage or delete comes from [rights]
 * for the active session only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BulkViewModel(
    private val catalog: ServerCatalog,
    private val playstate: Playstate,
    private val downloads: Downloads?,
    private val manage: Manage,
    private val rights: AdminRights,
    private val library: Library,
    private val afterDelete: (Session, String) -> Unit = { _, _ -> },
) : ViewModel() {
    private val running = MutableStateFlow(false)
    private val ask = MutableStateFlow<DeleteAsk?>(null)
    private val channel = Channel<BulkNotice>(Channel.BUFFERED)
    private val sessionNow = catalog.activeSession().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Snackbar lines, each once, only while their session is the one on screen. */
    val notices: Flow<BulkNotice> = channel.receiveAsFlow().filter { it.session == sessionNow.value }
    private val deletedNow = Channel<String>(Channel.BUFFERED)
    /** Each item the server has just deleted, so the screen showing that item's own page can leave it. */
    val deleted: Flow<String> = deletedNow.receiveAsFlow()

    private val rightsFor: Flow<Pair<Session?, AdminState?>> = sessionNow.flatMapLatest { s ->
        if (s == null) flowOf<Pair<Session?, AdminState?>>(null to null) else flow<Pair<Session?, AdminState?>> {
            emit(s to null)
            try { rights.known(s) } catch (e: CancellationException) { throw e } catch (e: Exception) { Unit }
            rights.state.collect { emit(s to it) }
        }
    }

    val state: StateFlow<BulkUiState> = combine(rightsFor, running, ask) { (s, held), run, a ->
        BulkUiState(
            session = s,
            canManage = s != null && held.isAdminFor(s),
            canDelete = s != null && held != null && held.session == s && held.mayDelete,
            running = run,
            deleteAsk = a?.takeIf { it.session == s },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BulkUiState())

    /** Runs [action] on [items] as the session on screen. DELETE_FROM_SERVER asks first; MANAGE and MORE are the screen's own. */
    fun apply(items: List<BulkItem>, action: BulkAction) {
        val s = state.value.session ?: return
        if (items.isEmpty()) return
        when (action) {
            BulkAction.MARK_WATCHED -> each(s, items, R.plurals.bulk_marked_watched) { playstate.setPlayed(s, it.id, true) }
            BulkAction.MARK_UNWATCHED -> each(s, items, R.plurals.bulk_marked_unwatched) { playstate.setPlayed(s, it.id, false) }
            BulkAction.FAVOURITE -> each(s, items, R.plurals.bulk_favourited) { playstate.setFavourite(s, it.id, true) }
            BulkAction.UNFAVOURITE -> each(s, items, R.plurals.bulk_unfavourited) { playstate.setFavourite(s, it.id, false) }
            BulkAction.DOWNLOAD -> each(s, items, R.plurals.bulk_downloading) { download(s, it) }
            BulkAction.REMOVE_DOWNLOAD -> each(s, items, R.plurals.bulk_download_removed) { downloads?.remove(s, it.id) }
            BulkAction.DELETE_FROM_SERVER -> askDelete(s, items)
            BulkAction.MANAGE, BulkAction.MORE -> Unit // the screen opens its own sheet or route
        }
    }

    /**
     * Asks the server, item by item, whether this profile may delete it (`CanDelete`); only those are offered. The rest are said
     * ("The server won't let you delete 2 of these."), and if none may be, nothing is asked.
     */
    private fun askDelete(s: Session, items: List<BulkItem>) {
        if (!state.value.canDelete) return
        viewModelScope.launch {
            val allowed = ArrayList<BulkItem>()
            var refused = 0
            var firstFailure: Exception? = null
            for (item in items) {
                try {
                    if (manage.canDelete(s, item.id)) allowed += item else refused++
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (firstFailure == null) firstFailure = e
                }
            }
            if (sessionNow.value != s) return@launch
            val unread = items.size - allowed.size - refused
            if (refused > 0) channel.trySend(BulkNotice(s, R.plurals.bulk_delete_not_allowed, refused))
            if (allowed.isEmpty()) {
                if (refused == 0) channel.trySend(BulkNotice(s, text = failureText(firstFailure)))
                return@launch
            }
            if (unread > 0) channel.trySend(BulkNotice(s, R.plurals.bulk_not_done, unread))
            val saved = allowed.associate { it.id to downloadsUnder(downloads, s, it) }.filterValues { it.isNotEmpty() }
            if (sessionNow.value == s) ask.value = DeleteAsk(s, allowed, saved)
        }
    }

    fun setKeepCopy(keep: Boolean) = ask.update { it?.copy(keepCopy = keep) }

    fun dismissDelete() {
        ask.value = null
    }

    /**
     * Deletes each confirmed item once, as the session that asked; each leaves this profile's lists at once. While another bar
     * action runs, Confirm is off and this does nothing: the question stays open until it can be sent (never silently dropped).
     */
    fun confirmDelete() {
        val a = ask.value ?: return
        if (a.session != sessionNow.value) {
            ask.value = null
            return
        }
        if (running.value) return
        ask.value = null
        each(a.session, a.items, R.plurals.bulk_deleted) { item ->
            manage.delete(a.session, item.id)
            deletedNow.trySend(item.id)
            // Deleted on the server: done from here, whatever happens on the phone.
            tidyAfterServerDelete(downloads, a.session, item.id, a.downloaded[item.id].orEmpty(), a.keepCopy) { id, keep ->
                library.forgetDeleted(a.session, id, keep)
            }
            try {
                afterDelete(a.session, item.id)
            } catch (e: Exception) {
                Unit
            }
        }
    }

    private suspend fun download(s: Session, item: BulkItem) {
        val d = downloads ?: throw AppError.of(AppError.Kind.UNKNOWN)
        val target = when (item.kind) {
            ItemKind.MOVIE, ItemKind.EPISODE -> DownloadTarget.Item(item.id)
            ItemKind.SERIES -> DownloadTarget.Series(item.id)
            ItemKind.SEASON -> item.seriesId?.let { DownloadTarget.Season(it, item.id) } ?: throw AppError.of(AppError.Kind.UNKNOWN)
            else -> throw AppError.of(AppError.Kind.UNKNOWN)
        }
        val plan = d.prepare(s, target, d.settings.value.defaultQuality ?: DownloadQuality.ORIGINAL)
        if (!plan.enoughSpace || plan.items.isEmpty()) throw NotStarted()
        d.start(s, plan)
    }

    /** A download that was planned but not started (no room, or nothing in it to download). */
    private class NotStarted : Exception("Not started.")

    /**
     * Runs [act] for each item in turn; one notice for them all: the plural for the ones that worked, or the reason the first
     * failure gave when none did (offline, refused...).
     */
    private fun each(s: Session, items: List<BulkItem>, @PluralsRes done: Int, act: suspend (BulkItem) -> Unit) {
        if (!running.compareAndSet(expect = false, update = true)) return
        viewModelScope.launch {
            var worked = 0
            var firstFailure: Exception? = null
            try {
                for (item in items) {
                    if (sessionNow.value != s) break
                    try {
                        act(item)
                        worked++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (firstFailure == null) firstFailure = e
                    }
                }
            } finally {
                running.value = false
            }
            val failed = items.size - worked
            when {
                worked > 0 -> {
                    channel.trySend(BulkNotice(s, done, worked))
                    if (failed > 0) channel.trySend(BulkNotice(s, R.plurals.bulk_not_done, failed))
                }
                firstFailure != null -> channel.trySend(BulkNotice(s, text = failureText(firstFailure)))
            }
        }
    }

    private fun failureText(e: Exception?): Int = when ((e as? AppError)?.kind) {
        AppError.Kind.OFFLINE -> R.string.bulk_offline
        AppError.Kind.FORBIDDEN, AppError.Kind.UNAUTHORISED -> R.string.bulk_refused
        AppError.Kind.SERVER_NOT_FOUND -> R.string.bulk_gone
        else -> if (e is NotStarted) R.string.bulk_no_room else R.string.bulk_failed
    }
}
