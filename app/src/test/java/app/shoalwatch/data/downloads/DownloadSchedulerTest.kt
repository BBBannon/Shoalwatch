package app.shoalwatch.data.downloads

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** One unique work for the queue: Wi-Fi only by default, kept when kicked again, replaced when the network setting changes. */
@RunWith(RobolectricTestRunner::class)
class DownloadSchedulerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var workManager: WorkManager
    private lateinit var prefs: DownloadPrefs

    @Before fun initialise() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        workManager = WorkManager.getInstance(context)
        prefs = DownloadPrefs(context.getSharedPreferences("scheduler-test", 0).also { it.edit().clear().commit() })
    }

    private fun infos(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(DownloadScheduler.NAME).get()

    private fun pending() = infos().filter { !it.state.isFinished }

    @Test fun mobileDataOffNeedsAnUnmeteredNetworkAndOnAnyConnection() {
        val scheduler = DownloadScheduler(workManager, prefs)
        scheduler.kick()
        val off = pending().single()
        assertEquals(NetworkType.UNMETERED, off.constraints.requiredNetworkType)
        assertTrue(off.constraints.requiresStorageNotLow())
        prefs.setMobileData(true)
        scheduler.kick(replace = true)
        assertEquals(NetworkType.CONNECTED, pending().single().constraints.requiredNetworkType)
    }

    @Test fun theRequestBacksOffExponentiallyFromThirtySeconds() {
        val spec = DownloadScheduler.request(mobileData = false).workSpec
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(30_000L, spec.backoffDelayDuration)
    }

    @Test fun aSecondKickKeepsTheWorkAndChangingTheSettingReplacesIt() = runTest(UnconfinedTestDispatcher()) {
        val scheduler = DownloadScheduler(workManager, prefs)
        val job = scheduler.watchSettings(backgroundScope) { true }
        scheduler.kick()
        val first = pending().single().id
        scheduler.kick()
        assertEquals("kept", first, pending().single().id)
        prefs.setMobileData(true)
        val replaced = pending().single()
        assertNotEquals("replaced", first, replaced.id)
        assertEquals(NetworkType.CONNECTED, replaced.constraints.requiredNetworkType)
        job.cancel()
    }

    @Test fun comingToTheForegroundWithQueuedRowsKicks() = runTest {
        val scheduler = DownloadScheduler(workManager, prefs)
        scheduler.inFront { false }
        assertTrue("nothing queued: nothing kicked", pending().isEmpty())
        scheduler.inFront { true }
        assertEquals(1, pending().size)
    }

    @Test fun theStatusStartsIdleAndFollowsWhatIsReported() {
        val scheduler = DownloadScheduler(workManager, prefs)
        assertEquals(QueueStatus.Idle, scheduler.status.value)
        scheduler.report(QueueStatus.Running(1, 3, null))
        assertEquals(QueueStatus.Running(1, 3, null), scheduler.status.value)
    }
}
