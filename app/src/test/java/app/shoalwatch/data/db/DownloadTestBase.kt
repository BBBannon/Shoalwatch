package app.shoalwatch.data.db

import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.db.downloads.DownloadStore
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.DownloadState
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.runBlocking
import org.junit.Before

/** [LibraryTestBase] plus a [DownloadStore] on the same in-memory database, a settable clock and helpers for a small series. */
abstract class DownloadTestBase : LibraryTestBase() {
    protected var clock = 1_000L
    internal lateinit var store: DownloadStore

    @Before fun openStore() { store = DownloadStore(db, { clock }) }

    protected val series get() = item("show", ItemKind.SERIES, "Show")
    protected val season1 get() = item("season1", ItemKind.SEASON, "Season 1", seriesId = "show", parentId = "show", indexNumber = 1)
    protected val season2 get() = item("season2", ItemKind.SEASON, "Season 2", seriesId = "show", parentId = "show", indexNumber = 2)
    protected fun ep(label: String, season: Int, number: Int) = episode(label, season, number)

    /** The server showed [s] these items (writes them and records visibility, as a list page would). */
    protected fun seen(s: Session, vararg items: Item) = runBlocking { library.writeItems(s, items.map { row(it) }, t0, requestedAtSeq = 0) }

    internal fun newDownload(item: Item, quality: DownloadQuality = DownloadQuality.ORIGINAL) = DownloadStore.NewDownload(
        itemId = item.id, kind = item.kind, seriesId = item.seriesId, seasonId = item.seasonId, quality = quality,
        storage = StorageKind.INTERNAL, volumeId = null, runtimeMs = item.runtimeMs, audioIndex = null,
    )

    protected fun key(item: Item, serverId: String = s1u1.serverId) = DownloadKey(serverId, item.id)

    /** The server confirmed [s]'s own CanDownload for [items] (the strict sharing rule: needed to see or inherit a shared copy). */
    protected fun confirm(s: Session, vararg items: Item) = runBlocking { for (it in items) store.confirm(DownloadKey(s.serverId, it.id), s.userId) }

    /** [s] adds a copy already here, as a share-only plan with its own permission confirmed. */
    internal fun shareDownload(item: Item) = newDownload(item).let { n ->
        DownloadStore.NewDownload(n.itemId, n.kind, n.seriesId, n.seasonId, n.quality, n.storage, n.volumeId, n.runtimeMs, null, confirmed = true, shareOnly = true)
    }

    protected fun pinned(label: String, serverId: String = s1u1.serverId): Boolean =
        count("items", "serverId = '$serverId' AND itemId = '${id(label)}' AND pinned = 1") == 1

    /** Marks the download [state] directly (the queue's job in later tasks). */
    protected fun setState(item: Item, state: DownloadState, serverId: String = s1u1.serverId) = runBlocking {
        store.update(DownloadKey(serverId, item.id)) { it.copy(state = state) }
    }
}
