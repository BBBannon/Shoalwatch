package app.shoalwatch.ui.photos

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.core.AppError
import app.shoalwatch.data.discover.BrowseFilter
import app.shoalwatch.data.discover.Discover
import app.shoalwatch.data.library.Freshness
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.Library
import app.shoalwatch.data.library.ListShape
import app.shoalwatch.data.library.Sort
import app.shoalwatch.data.library.SortBy
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.lists.ListsPhase
import app.shoalwatch.ui.lists.phaseFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PhotoViewerState(
    /** The profile everything below belongs to; null while nobody is signed in. */
    val session: Session? = null,
    val phase: ListsPhase = ListsPhase.LOADING,
    /** The folder's photos in name order (at most [PhotoViewerViewModel.MAX_PHOTOS]), the tapped one among them. */
    val photos: List<Item> = emptyList(),
    /** Where the pager starts: the tapped photo. */
    val startIndex: Int = 0,
) {
    override fun toString() = "PhotoViewerState(<redacted>)"
}

/**
 * The full-screen photo viewer's list (Milestone 11, live). It reads the direct photo children of [parentId] a page at a time
 * (at most [MAX_PHOTOS]), photos only, and starts at [photoId]. With no [parentId] (a photo opened from a playlist) the photo's
 * own folder is found from the photo itself; if the photo is not among what was read (a very large folder, or a playlist) it is
 * shown first so the tapped picture is always the one that opens. Restarts when the profile changes; nothing of the old one lingers.
 */
class PhotoViewerViewModel(
    private val photoId: String,
    private val parentId: String?,
    catalog: ServerCatalog,
    private val discover: Discover,
    private val library: Library,
) : ViewModel() {
    private val body = MutableStateFlow(PhotoViewerState())
    val state: StateFlow<PhotoViewerState> = body.asStateFlow()

    private var used = false

    init {
        viewModelScope.launch {
            catalog.activeSession().collectLatest { s ->
                body.value = PhotoViewerState(session = s, phase = if (s == null) ListsPhase.READY else ListsPhase.LOADING)
                if (s != null) read(s)
            }
        }
    }

    private suspend fun read(s: Session) {
        try {
            var tapped: Item? = null
            val folder = parentId ?: run {
                tapped = tappedItem(s)
                tapped.parentId
            }
            val photos = if (folder == null) emptyList() else folderPhotos(s, folder)
            val found = photos.indexOfFirst { it.id == photoId }
            val all = if (found >= 0) photos else listOf(tapped ?: tappedItem(s)) + photos
            body.update {
                if (it.session != s) it else it.copy(phase = ListsPhase.READY, photos = all, startIndex = maxOf(found, 0))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            body.update { if (it.session == s) it.copy(phase = phaseFor(e), photos = emptyList()) else it }
        }
    }

    /** The photo's own record: the first answer that has it, or the failure that says why not (offline, gone). */
    private suspend fun tappedItem(s: Session): Item {
        val detail = library.item(s, photoId).first { it.item != null || it.freshness is Freshness.Stale }
        detail.item?.let { return it.item }
        throw AppError.of((detail.freshness as? Freshness.Stale)?.reason ?: AppError.Kind.SERVER_NOT_FOUND)
    }

    private suspend fun folderPhotos(s: Session, folder: String): List<Item> {
        val shape = ListShape(setOf(ItemKind.PHOTO), recursive = false)
        val sort = Sort(SortBy.NAME, descending = false)
        val out = LinkedHashMap<String, Item>()
        var start = 0
        while (out.size < MAX_PHOTOS) {
            val page = discover.browse(s, folder, shape, BrowseFilter(), sort, start)
            page.items.forEach { if (it.item.kind == ItemKind.PHOTO && out.size < MAX_PHOTOS) out.putIfAbsent(it.item.id, it.item) }
            if (page.endReached || page.nextStartIndex <= start) break
            start = page.nextStartIndex
        }
        return out.values.toList()
    }

    /** Try again (offline or failed). */
    fun retry() {
        val s = body.value.session ?: return
        body.update { it.copy(phase = ListsPhase.LOADING) }
        viewModelScope.launch { read(s) }
    }

    /** Runs [action] unless a tap already did (Close, tapped twice, leaves once). */
    fun once(action: () -> Unit) {
        if (used) return
        used = true
        action()
    }

    companion object {
        /** The most photos one viewer holds. */
        const val MAX_PHOTOS = 500
    }
}
