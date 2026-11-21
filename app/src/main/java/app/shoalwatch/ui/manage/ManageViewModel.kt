package app.shoalwatch.ui.manage

import androidx.lifecycle.viewModelScope
import app.shoalwatch.R
import app.shoalwatch.data.admin.AdminRights
import app.shoalwatch.data.downloads.Downloads
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.Library
import app.shoalwatch.data.manage.Manage
import app.shoalwatch.data.manage.RefreshMode
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.admin.AdminGate
import app.shoalwatch.ui.admin.AdminNotice
import app.shoalwatch.ui.admin.AdminScreenModel
import app.shoalwatch.ui.browse.BulkItem
import app.shoalwatch.ui.browse.DeleteAsk
import app.shoalwatch.ui.browse.downloadsUnder
import app.shoalwatch.ui.browse.tidyAfterServerDelete
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/** The item being managed, as this phone has it (its name and kind only). */
data class ManageItem(val id: String, val title: String, val kind: ItemKind, val seriesId: String?) {
    override fun toString() = "ManageItem($kind)"
}

/** The refresh dialog's choices (plan decision 11); "Search for missing details" first, as the web dashboard. */
data class RefreshAsk(val mode: RefreshMode = RefreshMode.MISSING, val replaceImages: Boolean = false)

data class ManageUiState(
    val frame: app.shoalwatch.ui.admin.AdminFrame = app.shoalwatch.ui.admin.AdminFrame(),
    val item: ManageItem? = null,
    val refreshAsk: RefreshAsk? = null,
    val deleteAsk: DeleteAsk? = null,
    /** The profile's policy allows deleting (a hint: the item's own `CanDelete` decides when Delete is pressed). */
    val canDelete: Boolean = false,
    /** The item was deleted from the server: the route leaves the management screens. */
    val deleted: Boolean = false,
) {
    override fun toString() = "ManageUiState(<redacted>)"
}

/**
 * What a management change does on this phone afterwards: the item's pictures leave the memory cache and the item is fetched
 * again (new names, new image tags, so new cache keys), so what changed shows straight away. A failure here is not the change
 * failing: the next refresh catches up.
 */
class AfterChange(private val library: Library, private val evict: (serverId: String, itemId: String) -> Unit) {
    suspend fun changed(s: Session, itemId: String) {
        evict(s.serverId, itemId)
        try {
            library.refresh(s, app.shoalwatch.data.library.RefreshTarget.Item(itemId))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Unit
        }
    }

    /** The item was deleted from the server: it leaves this profile's lists; with the download kept, its record stays. */
    suspend fun deleted(s: Session, itemId: String, keepRecord: Boolean) {
        evict(s.serverId, itemId)
        library.forgetDeleted(s, itemId, keepRecord)
    }
}

/**
 * The management hub for one item (Milestone 10): Identify (films and series), Pictures, Edit details, Refresh details and Delete
 * from server. An admin screen in every respect ([AdminScreenModel]: the session rule, one action at a time, sent once).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManageViewModel(
    private val itemId: String,
    catalog: ServerCatalog,
    private val rights: AdminRights,
    connections: ServerConnections,
    private val manage: Manage,
    library: Library,
    private val downloads: Downloads?,
    private val after: AfterChange,
    clock: () -> Instant = Instant::now,
) : AdminScreenModel(catalog, rights, connections, clock) {
    private val refreshAsk = MutableStateFlow<RefreshAsk?>(null)
    private val deleteAsk = MutableStateFlow<DeleteAsk?>(null)
    private val deleted = MutableStateFlow(false)

    /** The item as this phone has it, read only while the gate is open for its owner. */
    private val item: Flow<ManageItem?> = frames.map { (it.gate as? AdminGate.Open)?.session }.flatMapLatest { s ->
        if (s == null) flowOf(null) else library.item(s, itemId).map { d -> d.item?.item?.let { ManageItem(it.id, it.name, it.kind, it.seriesId) } }.catch { emit(null) }
    }

    private val deleting = combine(deleteAsk, deleted, rights.state) { d, gone, held -> Triple(d, gone, held) }

    val state: StateFlow<ManageUiState> = combine(frames, item, refreshAsk, deleting) { frame, it, r, (d, gone, held) ->
        val session = (frame.gate as? AdminGate.Open)?.session
        if (frame.gate == AdminGate.Closed) ManageUiState(frame = frame)
        else ManageUiState(frame, it, r, d, canDelete = session != null && held != null && held.session == session && held.mayDelete, deleted = gone)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ManageUiState())

    fun askRefresh() {
        if (frames.value.canAct) refreshAsk.value = RefreshAsk()
    }

    fun setRefresh(ask: RefreshAsk) = refreshAsk.update { it?.let { ask } }

    fun dismissRefresh() {
        refreshAsk.value = null
    }

    fun confirmRefresh() {
        val ask = refreshAsk.value ?: return
        refreshAsk.value = null
        work.run("refresh", { s -> manage.refresh(s, itemId, ask.mode, ask.replaceImages) }) {
            notify(AdminNotice(R.string.manage_refresh_started))
        }
    }

    /**
     * Asks the server first whether this profile may delete the item (its own `CanDelete`): if not, says so and asks nothing. Then
     * the question, offering to keep what is downloaded under it (a series' or season's episodes too).
     */
    fun askDelete() {
        val gate = frames.value.gate as? AdminGate.Open ?: return
        val shown = state.value.item ?: return
        if (!state.value.canDelete) return
        val item = BulkItem(shown.id, shown.title, shown.kind, shown.seriesId)
        work.run("askDelete", { s ->
            if (!manage.canDelete(s, itemId)) {
                notify(AdminNotice(R.string.manage_delete_not_allowed))
                return@run
            }
            val saved = downloadsUnder(downloads, s, item)
            if (frames.value.gate == gate) deleteAsk.value = DeleteAsk(s, listOf(item), if (saved.isEmpty()) emptyMap() else mapOf(itemId to saved))
        })
    }

    fun setKeepCopy(keep: Boolean) = deleteAsk.update { it?.copy(keepCopy = keep) }

    fun dismissDelete() {
        deleteAsk.value = null
    }

    fun confirmDelete() {
        val ask = deleteAsk.value ?: return
        deleteAsk.value = null
        work.run("delete", { s ->
            manage.delete(s, itemId)
            // Deleted on the server: done from here, whatever happens on the phone.
            tidyAfterServerDelete(downloads, s, itemId, ask.downloaded[itemId].orEmpty(), ask.keepCopy) { id, keep -> after.deleted(s, id, keep) }
        }) {
            notify(AdminNotice(R.string.manage_deleted))
            deleted.value = true
        }
    }

    /** Pull-to-refresh is not offered; Refresh in the bar fetches the item again. */
    fun reload() {
        val s = (frames.value.gate as? AdminGate.Open)?.session ?: return
        viewModelScope.launch { after.changed(s, itemId) }
    }

    override fun dropLoaded() {
        refreshAsk.value = null
        deleteAsk.value = null
    }
}
