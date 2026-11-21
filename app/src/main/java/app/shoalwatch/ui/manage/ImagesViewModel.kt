package app.shoalwatch.ui.manage

import android.net.Uri
import androidx.lifecycle.viewModelScope
import app.shoalwatch.R
import app.shoalwatch.data.admin.AdminRights
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.Library
import app.shoalwatch.data.manage.ImageKind
import app.shoalwatch.data.manage.ItemImage
import app.shoalwatch.data.manage.Manage
import app.shoalwatch.data.manage.PictureRefused
import app.shoalwatch.data.manage.PreparedImage
import app.shoalwatch.data.manage.RemoteImage
import app.shoalwatch.data.manage.RemoteImages
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.admin.AdminFrame
import app.shoalwatch.ui.admin.AdminGate
import app.shoalwatch.ui.admin.AdminNotice
import app.shoalwatch.ui.admin.AdminScreenModel
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
import kotlinx.coroutines.launch
import java.time.Instant

data class ImagesUiState(
    val frame: AdminFrame = AdminFrame(),
    val session: Session? = null,
    val kind: ImageKind = ImageKind.PRIMARY,
    val images: List<ItemImage> = emptyList(),
    /** The item as this phone has it: its own poster, backdrop and thumbnail are shown beside their entries. */
    val item: ItemWithUserData? = null,
    /** The online choices for [kind], once asked for. */
    val remote: RemoteImages? = null,
    val allLanguages: Boolean = false,
    val removeAsk: ItemImage? = null,
    val preparing: Boolean = false,
    /** Why a picture from the phone was not used, as words. */
    val pickProblem: Int? = null,
) {
    override fun toString() = "ImagesUiState(<redacted>)"
}

/**
 * Pictures (Milestone 10, plan decisions 7 to 9). Per kind: what the server has now (`GET /Items/{id}/Images`), the online choices
 * (listed in words, never loaded on the phone), an upload from the photo picker (prepared on the phone, then sent once) and
 * Remove (confirmed). After every change the list is read again and the item fetched again, so the new picture shows.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ImagesViewModel(
    private val itemId: String,
    catalog: ServerCatalog,
    rights: AdminRights,
    connections: ServerConnections,
    private val manage: Manage,
    library: Library,
    private val prepare: suspend (Uri, ImageKind) -> PreparedImage,
    private val after: AfterChange,
    clock: () -> Instant = Instant::now,
) : AdminScreenModel(catalog, rights, connections, clock) {
    private val images = MutableStateFlow<List<ItemImage>>(emptyList())
    private val kind = MutableStateFlow(ImageKind.PRIMARY)
    private val remote = MutableStateFlow<RemoteImages?>(null)
    private val allLanguages = MutableStateFlow(false)
    private val removeAsk = MutableStateFlow<ItemImage?>(null)
    private val preparing = MutableStateFlow(false)
    private val pickProblem = MutableStateFlow<Int?>(null)

    private val poll = poller { s ->
        val list = manage.images(s, itemId)
        if (guard.gate.value is AdminGate.Open) images.value = list
        null
    }

    private val item: Flow<ItemWithUserData?> = frames.map { (it.gate as? AdminGate.Open)?.session }.flatMapLatest { s ->
        if (s == null) flowOf(null) else library.item(s, itemId).map { it.item }.catch { emit(null) }
    }

    private data class Choices(val kind: ImageKind, val remote: RemoteImages?, val all: Boolean, val remove: ItemImage?, val preparing: Boolean, val problem: Int?)

    private val choices: Flow<Choices> = combine(kind, remote, allLanguages, removeAsk, combine(preparing, pickProblem) { p, q -> p to q }) { k, r, a, rm, pq ->
        Choices(k, r, a, rm, pq.first, pq.second)
    }

    val state: StateFlow<ImagesUiState> = combine(frames, images, item, choices) { frame, list, it, c ->
        if (frame.gate == AdminGate.Closed) ImagesUiState(frame = frame)
        else ImagesUiState(frame, (frame.gate as? AdminGate.Open)?.session, c.kind, list, it, c.remote, c.all, c.remove, c.preparing, c.problem)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ImagesUiState())

    fun refresh() = poll.kick()

    fun chooseKind(k: ImageKind) {
        if (kind.value == k) return
        kind.value = k
        remote.value = null
        pickProblem.value = null
    }

    fun findOnline() {
        val k = kind.value
        val all = allLanguages.value
        work.run("find", { s ->
            val found = manage.remoteImages(s, itemId, k, all)
            if (guard.gate.value is AdminGate.Open && kind.value == k) remote.value = found
        })
    }

    fun setAllLanguages(on: Boolean) {
        allLanguages.value = on
        if (remote.value != null) findOnline()
    }

    fun use(image: RemoteImage) {
        if (remote.value?.images?.contains(image) != true) return
        work.run("use", { s ->
            manage.useRemoteImage(s, itemId, image)
            after.changed(s, itemId)
        }) {
            notify(AdminNotice(R.string.images_saved))
            poll.kick()
        }
    }

    /** A picture chosen in the photo picker: prepared on the phone first (no network), then sent once. */
    fun upload(uri: Uri) {
        val gate = guard.gate.value as? AdminGate.Open ?: return
        if (!frames.value.canAct || preparing.value) return
        val k = kind.value
        preparing.value = true
        pickProblem.value = null
        viewModelScope.launch {
            val prepared = try {
                prepare(uri, k)
            } catch (e: CancellationException) {
                throw e
            } catch (e: PictureRefused) {
                pickProblem.value = when (e.reason) {
                    PictureRefused.Reason.NOT_A_PICTURE -> R.string.images_not_picture
                    PictureRefused.Reason.TOO_LARGE -> R.string.images_too_big
                    PictureRefused.Reason.UNREADABLE -> R.string.images_unreadable
                }
                null
            } catch (e: Exception) {
                pickProblem.value = R.string.images_unreadable
                null
            } finally {
                preparing.value = false
            }
            if (prepared == null || guard.gate.value != gate) return@launch
            work.run("upload", { s ->
                manage.uploadImage(s, itemId, k, prepared)
                after.changed(s, itemId)
            }) {
                notify(AdminNotice(R.string.images_saved))
                poll.kick()
            }
        }
    }

    fun askRemove(image: ItemImage) {
        if (frames.value.canAct && image in images.value) removeAsk.value = image
    }

    fun dismissRemove() {
        removeAsk.value = null
    }

    fun confirmRemove() {
        val image = removeAsk.value ?: return
        removeAsk.value = null
        if (image !in images.value) return
        work.run("remove", { s ->
            manage.deleteImage(s, itemId, image.kind, image.index)
            after.changed(s, itemId)
        }) {
            notify(AdminNotice(R.string.images_removed))
            poll.kick()
        }
    }

    fun dismissPickProblem() {
        pickProblem.value = null
    }

    override fun dropLoaded() {
        images.value = emptyList()
        remote.value = null
        removeAsk.value = null
        pickProblem.value = null
    }
}
