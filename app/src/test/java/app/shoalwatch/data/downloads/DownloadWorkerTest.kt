package app.shoalwatch.data.downloads

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import app.shoalwatch.core.AppError
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The worker only drains the queue: success when empty, retry otherwise, a data-sync foreground notice, no input data. */
@RunWith(RobolectricTestRunner::class)
class DownloadWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val statuses = mutableListOf<QueueStatus>()
    private val reported = CountDownLatch(1)
    private var drain: QueueDrain = QueueDrain { _, _ -> QueueRun.Drained }
    private var mobileData = false
    private var ready = 0
    /** Every foreground notice the worker set, in order (review Minor 5: deleting a `setForeground` must fail a test). */
    private val foregrounds = mutableListOf<androidx.work.ForegroundInfo>()
    private val recordingUpdater = androidx.work.ForegroundUpdater { _, _, info ->
        synchronized(foregrounds) { foregrounds += info }
        com.google.common.util.concurrent.Futures.immediateFuture(null)
    }

    private fun parts() = DownloadWorker.Parts(
        queue = { stop, progress -> drain.drain(stop, progress) }, notifications = DownloadNotifications(context),
        report = { s -> synchronized(statuses) { statuses += s }; if (s != QueueStatus.Idle) reported.countDown() },
        readySince = { ready }, mobileData = { mobileData }, now = { 0L },
    )

    /** Waits up to 5 s, letting the main looper run meanwhile (Robolectric pauses it). */
    private fun CountDownLatch.awaitIdling(): Boolean {
        repeat(100) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (await(50, TimeUnit.MILLISECONDS)) return true
        }
        return false
    }

    private fun worker(): DownloadWorker = TestListenableWorkerBuilder<DownloadWorker>(context).setWorkerFactory(ShoalwatchWorkerFactory { parts() })
        .setForegroundUpdater(recordingUpdater).build()

    private val manager get() = context.getSystemService(android.app.NotificationManager::class.java)

    private fun progressOf(info: androidx.work.ForegroundInfo) = info.notification.extras.getInt(android.app.Notification.EXTRA_PROGRESS)

    @Test fun theDrainRunsInTheForegroundAndEachNewStepUpdatesItsNotice() {
        drain = QueueDrain { _, progress ->
            progress(QueueStatus.Running(1, 2, 0.5f))
            progress(QueueStatus.Running(1, 2, 0.502f)) // the same percent: not shown again
            progress(QueueStatus.Running(2, 2, 0.25f))
            QueueRun.Drained
        }
        runBlocking { worker().doWork() }
        assertEquals("starting, then two progress notices", 3, foregrounds.size)
        assertTrue(foregrounds.all { it.notificationId == DownloadNotifications.ID && it.foregroundServiceType == ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC })
        assertEquals(listOf(50, 25), foregrounds.drop(1).map(::progressOf))
    }

    @Test fun anythingCompletedPostsTheFinishedNoticeAndNothingCompletedDoesNot() {
        runBlocking { worker().doWork() }
        assertTrue(org.robolectric.Shadows.shadowOf(manager).allNotifications.isEmpty())
        ready = 3
        runBlocking { worker().doWork() }
        val finished = org.robolectric.Shadows.shadowOf(manager).allNotifications.single()
        assertEquals("Downloads finished", finished.extras.getCharSequence(android.app.Notification.EXTRA_TITLE).toString())
    }

    @Test fun anEmptiedQueueIsSuccess() {
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker().doWork() })
        assertEquals(QueueStatus.Idle, statuses.last())
    }

    @Test fun aRetryFromTheQueueIsRetryAndSaysItIsWaiting() {
        drain = QueueDrain { _, _ -> QueueRun.Retry(AppError.Kind.NETWORK) }
        assertEquals(ListenableWorker.Result.retry(), runBlocking { worker().doWork() })
        assertEquals(QueueStatus.WaitingForConnection, statuses.last())
    }

    /** Tasks 11-12 review Minor 7: a failure escaping the drain (a full database, say) retries the work, never fails it for good. */
    @Test fun anUnexpectedFailureInTheDrainIsRetryNotFailure() {
        drain = QueueDrain { _, _ -> throw IllegalStateException("database or disk is full") }
        assertEquals(ListenableWorker.Result.retry(), runBlocking { worker().doWork() })
        assertEquals(QueueStatus.Idle, statuses.last())
    }

    @Test fun stoppedIsRetry() {
        drain = QueueDrain { _, _ -> QueueRun.Stopped }
        assertEquals(ListenableWorker.Result.retry(), runBlocking { worker().doWork() })
    }

    @Test fun theForegroundIsADataSyncNoticeWithItsOwnId() {
        val info = runBlocking { worker().getForegroundInfo() }
        assertEquals(DownloadNotifications.ID, info.notificationId)
        assertEquals(4201, info.notificationId)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, info.foregroundServiceType)
    }

    @Test fun progressIsPassedOnAsItComes() {
        drain = QueueDrain { _, progress -> progress(QueueStatus.Running(1, 2, 0.5f)); progress(QueueStatus.Running(2, 2, null)); QueueRun.Drained }
        runBlocking { worker().doWork() }
        assertEquals(listOf(QueueStatus.Running(1, 2, 0.5f), QueueStatus.Running(2, 2, null), QueueStatus.Idle), statuses)
    }

    @Test fun theSixHourLimitPausesItUntilTheAppIsOpenedAgain() {
        val draining = CountDownLatch(1)
        drain = QueueDrain { _, _ -> draining.countDown(); awaitCancellation() }
        val w = worker()
        val future = w.startWork()
        assertTrue("draining", draining.awaitIdling())
        w.stop(WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT) // as WorkManager stops a worker: the reason, then the future cancelled
        future.cancel(true)
        assertTrue("reported: $statuses stop=${w.stopReason} stopped=${w.isStopped} done=${future.isDone}", reported.awaitIdling())
        assertTrue(future.isCancelled || future.isDone)
        assertEquals(QueueStatus.PausedByTimeLimit, synchronized(statuses) { statuses.last() })
    }

    @Test fun losingWiFiWithMobileDataOffIsWaitingForWiFi() {
        val draining = CountDownLatch(1)
        drain = QueueDrain { _, _ -> draining.countDown(); awaitCancellation() }
        val w = worker()
        val future = w.startWork()
        assertTrue("draining", draining.awaitIdling())
        w.stop(WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY)
        future.cancel(true)
        assertTrue("reported", reported.awaitIdling())
        assertEquals(QueueStatus.WaitingForWifi, synchronized(statuses) { statuses.last() })
    }

    @Test fun theWorkerHasNoInputData() {
        assertEquals(0, worker().inputData.size())
        assertEquals("nothing in the request either", 0, DownloadScheduler.request(mobileData = false).workSpec.input.size())
    }

    @Test fun theFactoryBuildsOnlyTheDownloadWorker() {
        var captured: WorkerParameters? = null
        val recording = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
                captured = workerParameters
                return ShoalwatchWorkerFactory { parts() }.createWorker(appContext, workerClassName, workerParameters)
            }
        }
        assertTrue(TestListenableWorkerBuilder<DownloadWorker>(context).setWorkerFactory(recording).build() is DownloadWorker)
        assertEquals(null, ShoalwatchWorkerFactory { parts() }.createWorker(context, "com.example.Evil", captured!!))
        assertEquals(null, ShoalwatchWorkerFactory { parts() }.createWorker(context, "androidx.work.impl.workers.DiagnosticsWorker", captured!!))
    }
}
