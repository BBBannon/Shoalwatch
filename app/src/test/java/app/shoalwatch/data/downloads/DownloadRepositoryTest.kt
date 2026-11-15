package app.shoalwatch.data.downloads

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.S1
import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.db.downloads.RemoveOutcome
import app.shoalwatch.data.playback.fixtureInfo
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** The `Downloads` seam: a plan only for its own session, owners-only changes, and flows that never throw. */
class DownloadRepositoryTest : DownloadHousekeepingTestBase() {
    private val a get() = s1u1
    private val b get() = s1u2
    private val f1 get() = item("f1")
    private val f2 get() = item("f2")
    internal lateinit var repo: DownloadRepository
    private lateinit var prefs: DownloadPrefs

    @Before fun setUpRepository() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        prefs = DownloadPrefs(context.getSharedPreferences("repository-test", 0).also { it.edit().clear().commit() })
        val planner = DownloadPlanner(app.shoalwatch.ui.browse.FakeLibrary(), db.itemDao(), facts, { _, _ -> fixtureInfo("direct-play.json") }, db.downloadDao(), fake.volumes, prefs, kotlinx.coroutines.Dispatchers.Unconfined)
        repo = DownloadRepository(store, db.offlineLibraryDao(), db.itemDao(), planner, scheduler, prefs, fake.volumes, paths, scope, kotlinx.coroutines.Dispatchers.Unconfined)
    }

    private fun plan(s: app.shoalwatch.data.servers.Session, vararg items: app.shoalwatch.data.library.Item, shareOnly: Boolean = false) = DownloadPlan(
        session = s, requested = DownloadQuality.ORIGINAL, items = items.map { PlannedItem(it.id, DownloadQuality.ORIGINAL, 10, shareOnly, confirmed = shareOnly) }, skipped = emptyList(),
        convertedInstead = 0, totalBytes = 10, freeBytes = 1L shl 40, enoughSpace = true, storage = StorageKind.INTERNAL, volumeId = null, mobileData = false,
    )

    @Test fun aPlanMadeForAnotherSessionIsRefusedAndAddsNothing() = runBlocking {
        seen(a, f1)
        seen(b, f1)
        try {
            repo.start(b, plan(a, f1))
            fail("started another profile's plan")
        } catch (e: IllegalArgumentException) {
            assertEquals("A plan is for its own session.", e.message)
        }
        assertEquals(0, count("downloads"))
        assertFalse(kicked())
    }

    @Test fun startAddsWhatIsStillVisibleAndKicks() = runBlocking {
        seen(a, f1)
        repo.start(a, plan(a, f1, f2)) // f2 was never shown to A (or no longer is)
        assertEquals(listOf(f1.id), db.downloadDao().ownedItems(S1, a.userId))
        assertTrue(kicked())
    }

    @Test fun shareOnlyItemsAddOwnersWithoutKicking() = runBlocking {
        queued(a, f1)
        seen(b, f1)
        repo.start(b, plan(b, f1, shareOnly = true))
        assertEquals(listOf(a.userId, b.userId).sorted(), store.owners(DownloadKey(S1, f1.id)).sorted())
        assertFalse(kicked())
    }

    @Test fun removeGivesEachOutcome() = runBlocking {
        queued(a, f1)
        seen(b, f1)
        store.add(b, listOf(shareDownload(f1)))
        val files = filesOf(f1)
        assertEquals(RemoveOutcome.KeptForOthers, repo.remove(a, f1.id))
        assertTrue(files.exists())
        assertEquals(RemoveOutcome.NotYours, repo.remove(a, f1.id))
        assertEquals(RemoveOutcome.Deleted(DownloadKey(S1, f1.id)), repo.remove(b, f1.id))
        assertFalse(files.exists())
    }

    @Test fun pauseResumeRetryAndTheirAllFormsChangeOnlyTheCallersOwn() = runBlocking {
        queued(a, f1)
        queued(b, f2)
        repo.pause(b, f1.id)
        assertEquals(DownloadState.QUEUED, download(f1)!!.state)
        repo.pauseAll(a)
        assertEquals(DownloadState.PAUSED, download(f1)!!.state)
        assertEquals(DownloadState.QUEUED, download(f2)!!.state)
        repo.resume(b, f1.id)
        assertEquals(DownloadState.PAUSED, download(f1)!!.state)
        repo.resumeAll(a)
        assertEquals(DownloadState.QUEUED, download(f1)!!.state)
        assertTrue(kicked())
        store.update(DownloadKey(S1, f2.id)) { it.copy(state = DownloadState.FAILED, failure = DownloadFailure.NO_SPACE) }
        repo.retry(a, f2.id)
        assertEquals(DownloadState.FAILED, download(f2)!!.state)
        repo.retry(b, f2.id)
        assertEquals(DownloadState.QUEUED, download(f2)!!.state)
        repo.pause(a, f1.id); repo.resume(a, f1.id)
        assertEquals(DownloadState.QUEUED, download(f1)!!.state)
    }

    @Test fun aMalformedIdGivesAnEmptyValueAndNeverThrows() = runBlocking {
        assertNull(repo.status(a, "../System").first())
        assertEquals(DownloadRepository.EMPTY_SERIES, repo.series(a, "nope").first())
        assertEquals(emptyMap<String, DownloadInfo>(), repo.seriesStatus(a, "NOT-AN-ID").first())
        assertFalse(repo.sharing("x").first())
    }

    @Test fun aDatabaseFailureIsUnknownToActionsAndEmptyToFlows() = runBlocking {
        queued(a, f1)
        db.close()
        assertEquals(DownloadRepository.EMPTY_LIBRARY, repo.library(a).first())
        assertNull(repo.status(a, f1.id).first())
        try {
            repo.pause(a, f1.id)
            fail("no error")
        } catch (e: AppError) {
            assertEquals(AppError.Kind.UNKNOWN, e.kind)
        }
    }

    @Test fun theSettingsAreTheDevicesAndTheQueueIsTheSchedulers() {
        repo.setMobileData(true)
        assertTrue(repo.settings.value.mobileData)
        repo.setDefaultQuality(DownloadQuality.LOW)
        assertEquals(DownloadQuality.LOW, repo.settings.value.defaultQuality)
        scheduler.report(QueueStatus.WaitingForWifi)
        assertEquals(QueueStatus.WaitingForWifi, repo.queue.value)
        assertEquals(StorageKind.INTERNAL, repo.storageChoices().first().kind)
    }
}
