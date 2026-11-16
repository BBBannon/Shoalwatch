package app.shoalwatch.data.downloads

import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.db.downloads.DownloadStore
import app.shoalwatch.data.db.downloads.LibraryWriteLock
import app.shoalwatch.data.db.downloads.ReleaseStore
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.LibraryRepositoryTestBase
import app.shoalwatch.data.library.PlaystateRepository
import app.shoalwatch.data.library.UserData
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.sync.OutboxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The release rules on real parts: in-memory Room, the real [DownloadStore], [ReleaseStore], [PlaystateRepository] and outbox
 * (nothing sends, so every queued row stays to be read), [ReleasedFiles] over a [TemporaryFolder], and the real
 * [HousekeepingRepository] with [signedOut] profiles treated as having no token. A = U1, B = U2 on S1.
 */
abstract class ReleaseTestBase : LibraryRepositoryTestBase() {
    @get:Rule val temp = TemporaryFolder()

    internal lateinit var store: DownloadStore
    internal lateinit var releases: ReleaseStore
    internal lateinit var playstate: PlaystateRepository
    internal lateinit var outbox: OutboxStore
    internal lateinit var paths: DownloadPaths
    internal lateinit var playing: LocalPlaying
    internal lateinit var housekeeping: HousekeepingRepository
    protected lateinit var fake: FakeVolumes
    protected val signedOut = mutableSetOf<Session>()
    protected val watchedCalls = mutableListOf<Pair<Session, String>>()

    @Before fun openRelease() {
        store = DownloadStore(db, { now })
        releases = ReleaseStore(db, { now }, LibraryWriteLock.None)
        outbox = OutboxStore(db) { now }
        playstate = PlaystateRepository(repo, outbox, { }, appScope, Dispatchers.IO, { now })
        fake = FakeVolumes(temp.newFolder())
        paths = DownloadPaths(fake.volumes)
        playing = LocalPlaying()
        housekeeping = HousekeepingRepository(
            releases, db.housekeepingDao(), playstate, ReleasedFiles(paths, playing, appScope), { s, id -> synchronized(watchedCalls) { watchedCalls += s to id } },
            { s -> s !in signedOut }, appScope,
        )
    }

    protected val a get() = s1u1
    protected val b get() = s1u2

    protected val film get() = item("film")
    protected val show get() = item("show", ItemKind.SERIES, "Show")
    protected val season1 get() = item("season1", ItemKind.SEASON, "Season 1", seriesId = "show", parentId = "show", indexNumber = 1)
    protected val e1 get() = episode("e1", 1, 1)
    protected val e2 get() = episode("e2", 1, 2)

    /** The server showed [s] these items (and records visibility, as a list page would). */
    protected fun seen(s: Session, vararg items: Item, data: UserData? = null) =
        runBlocking { library.writeItems(s, items.map { row(it, data) }, t0, requestedAtSeq = 0) }

    internal fun newDownload(item: Item, confirmed: Boolean = false, shareOnly: Boolean = false) = DownloadStore.NewDownload(
        itemId = item.id, kind = item.kind, seriesId = item.seriesId, seasonId = item.seasonId, quality = DownloadQuality.ORIGINAL,
        storage = StorageKind.INTERNAL, volumeId = null, runtimeMs = item.runtimeMs, audioIndex = null, confirmed = confirmed, shareOnly = shareOnly,
    )

    /** [s] downloads [item] with its own sign-in, the copy is COMPLETE and its files are on disk. */
    protected fun downloaded(s: Session, item: Item) = runBlocking {
        store.add(s, listOf(newDownload(item, confirmed = true)))
        val key = DownloadKey(s.serverId, item.id)
        store.update(key) { it.copy(state = DownloadState.COMPLETE) }
        store.markFetcher(key, s.userId)
        files(key).also { it.mkdirs(); File(it, "media.mkv").writeText("x") }
    }

    /** [s] joins an existing copy as a second owner (planned as "already on this phone", its own permission confirmed). */
    protected fun alsoOwns(s: Session, item: Item) { runBlocking { store.add(s, listOf(newDownload(item, confirmed = true, shareOnly = true))) } }

    /** The server confirmed [s]'s own CanDownload (what a shared copy needs to be seen). */
    protected fun confirm(s: Session, item: Item) = runBlocking { store.confirm(DownloadKey(s.serverId, item.id), s.userId) }

    protected fun sharing(on: Boolean, rule: SharedDelete = SharedDelete.ANYONE) = runBlocking {
        store.setSharing(a.serverId, on)
        db.housekeepingDao().insertServerSettingsIfMissing(a.serverId)
        db.housekeepingDao().updateDeleteShared(a.serverId, rule)
    }

    protected fun files(key: DownloadKey): File = paths.itemFolder(key, StorageKind.INTERNAL, null)!!

    protected fun hasRow(item: Item, serverId: String = a.serverId) = count("downloads", "serverId = '$serverId' AND itemId = '${item.id}'") == 1

    protected fun tombstones(item: Item): Set<String> = runBlocking {
        db.openHelper.readableDatabase.query("SELECT userId FROM released_episodes WHERE itemId = '${item.id}'").use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }
    }

    protected fun pinned(item: Item, serverId: String = a.serverId) = count("items", "serverId = '$serverId' AND itemId = '${item.id}' AND pinned = 1") == 1

    /** The PLAYED rows queued in [s]'s own outbox for the item. */
    protected fun playedRows(s: Session, item: Item) =
        count("sync_outbox", "serverId = '${s.serverId}' AND userId = '${s.userId}' AND itemId = '${item.id}' AND kind = 'PLAYED'")

    protected fun playedHere(s: Session, item: Item): Boolean =
        count("user_data", "serverId = '${s.serverId}' AND userId = '${s.userId}' AND itemId = '${item.id}' AND played = 1") == 1

    /** Waits for the files job [ReleasedFiles] launched. */
    protected fun settled() = runBlocking { settle() }
}
