package app.shoalwatch.data.downloads

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.Library
import app.shoalwatch.data.library.RefreshTarget
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.data.servers.Session
import app.shoalwatch.security.SecretBox
import java.io.File
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before

/**
 * Housekeeping wired as the app wires it: a real [ServerStore] whose removal hooks call it, the queue over the same Room, a
 * card next to internal storage, and a test WorkManager to see kicks.
 */
abstract class DownloadHousekeepingTestBase : DownloadQueueTestBase() {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    protected val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val jobs = mutableListOf<Job>()
    internal lateinit var hooked: ServerStore
    internal lateinit var housekeeping: DownloadHousekeeping
    internal lateinit var scheduler: DownloadScheduler
    internal lateinit var card: File
    protected lateinit var workManager: WorkManager

    /** What the server says on a single-item refresh: ids listed here are refused (403), which revokes the record. */
    internal val refused = mutableSetOf<String>()
    internal val refreshedIds = mutableListOf<String>()

    private val fakeLibrary = object : Library by app.shoalwatch.ui.browse.FakeLibrary() {
        override suspend fun refresh(s: Session, target: RefreshTarget) {
            val id = (target as RefreshTarget.Item).itemId
            refreshedIds += id
            if (id in refused) library.revokeVisibility(s, id) else db.itemDao().markVisible(s.serverId, s.userId, listOf(id), clock)
        }
    }

    @Before fun setUpHousekeeping() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        workManager = WorkManager.getInstance(context)
        card = tmp.newFolder("card")
        fake.card(CARD, card)
        fake.free[CARD] = 1L shl 40
        scheduler = DownloadScheduler(workManager, DownloadPrefs(context.getSharedPreferences("housekeeping-test", 0).also { it.edit().clear().commit() }))
        housekeeping = DownloadHousekeeping(store, paths, queue(), scheduler, fakeLibrary, scope, Dispatchers.Unconfined) { clock }
        hooked = ServerStore(
            db, SecretBox { key },
            onServerRemoved = { jobs += housekeeping.onServerRemoved(it) },
            onAccountGone = { s, u -> jobs += housekeeping.onAccountGone(s, u) },
        )
    }

    @After fun stopScope() { scope.coroutineContext[Job]?.cancel() }

    /** Waits for every hook's work. */
    protected fun settle() = runBlocking { while (jobs.isNotEmpty()) jobs.removeAt(0).join() }

    /** A file in [item]'s folder on [storage], as a download leaves it. */
    internal fun filesOf(item: Item, storage: StorageKind = StorageKind.INTERNAL, serverId: String = s1u1.serverId): File {
        val folder = paths.itemFolder(DownloadKey(serverId, item.id), storage, if (storage == StorageKind.REMOVABLE) CARD else null)!!
        folder.mkdirs()
        return File(folder, "media.mkv").apply { writeBytes(ByteArray(10)) }
    }

    protected fun kicked() = workManager.getWorkInfosForUniqueWork(DownloadScheduler.NAME).get().any { !it.state.isFinished }

    internal fun complete(vararg items: Item, serverId: String = s1u1.serverId) = runBlocking {
        for (it in items) store.update(DownloadKey(serverId, it.id)) { row -> row.copy(state = DownloadState.COMPLETE) }
    }

    protected companion object {
        const val CARD = "CARD-1"
    }
}
