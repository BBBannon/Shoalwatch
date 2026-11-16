package app.shoalwatch.data.sync

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import app.shoalwatch.core.AppError
import app.shoalwatch.data.downloads.DownloadNotifications
import app.shoalwatch.data.downloads.DownloadWorker
import app.shoalwatch.data.downloads.QueueRun
import app.shoalwatch.data.downloads.ShoalwatchWorkerFactory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sync job only awaits drainAll: nothing to send or only a sign-in missing is success, anything worth another go is retry. */
class SyncWorkerTest : SyncSenderTestBase() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val parts = DownloadWorker.Parts(
        queue = { _, _ -> QueueRun.Drained }, notifications = DownloadNotifications(context), report = {},
        readySince = { 0 }, mobileData = { false }, now = { 0L },
    )
    private val factory get() = ShoalwatchWorkerFactory({ parts }, { sender })

    private fun run(): ListenableWorker.Result = runBlocking {
        TestListenableWorkerBuilder<SyncWorker>(context).setWorkerFactory(factory).build().doWork()
    }

    @Test fun anEmptyOutboxIsSuccess() {
        assertEquals(ListenableWorker.Result.success(), run())
    }

    @Test fun rowsThatAllGoAreSuccess() {
        enqueue(s1u1, favourite("a"))
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(0, count("sync_outbox"))
    }

    @Test fun waitingForASignInIsSuccessBecauseTheSignInKicksAgain() {
        signedOut += s1u1
        enqueue(s1u1, favourite("a"))
        assertEquals(ListenableWorker.Result.success(), run())
        assertEquals(1, count("sync_outbox"))
    }

    @Test fun noConnectionIsRetry() {
        enqueue(s1u1, favourite("a"))
        gateway.failNext("favourite", AppError.Kind.OFFLINE)
        assertEquals(ListenableWorker.Result.retry(), run())
    }

    @Test fun aDeferredRowIsRetry() {
        enqueue(s1u1, favourite("a"))
        gateway.failNext("favourite", AppError.Kind.SERVER_ERROR)
        assertEquals(ListenableWorker.Result.retry(), run())
    }

    @Test fun aStoppedDrainIsRetry() {
        enqueue(s1u1, favourite("a"))
        landing.fail = SQLiteException("disk")
        assertEquals(ListenableWorker.Result.retry(), run())
    }

    @Test fun oneProfileWaitingForASignInDoesNotHideAnotherWaitingForTheConnection() {
        signedOut += s1u2
        enqueue(s1u2, favourite("a"))
        enqueue(s2u1, favourite("b"))
        gateway.failNext("favourite", AppError.Kind.OFFLINE)
        assertEquals(ListenableWorker.Result.retry(), run())
    }

    @Test fun theFactoryBuildsBothWorkersAndNothingElse() {
        var captured: WorkerParameters? = null
        val recording = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? {
                captured = workerParameters
                return factory.createWorker(appContext, workerClassName, workerParameters)
            }
        }
        assertTrue(TestListenableWorkerBuilder<SyncWorker>(context).setWorkerFactory(recording).build() is SyncWorker)
        assertTrue(factory.createWorker(context, DownloadWorker::class.java.name, captured!!) is DownloadWorker)
        assertNull(factory.createWorker(context, "com.example.Evil", captured!!))
        assertEquals("no input data", 0, captured!!.inputData.size())
    }
}
