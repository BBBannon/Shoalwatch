package app.shoalwatch.data.downloads

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkInfo
import androidx.work.WorkerParameters
import app.shoalwatch.core.AppLog
import kotlinx.coroutines.CancellationException

/** What the worker drains (`DownloadQueue` in the app, a fake in tests). */
internal fun interface QueueDrain {
    suspend fun drain(shouldStop: () -> Boolean, onProgress: suspend (QueueStatus) -> Unit): QueueRun
}

/**
 * The one unique worker: a foreground `dataSync` worker that only drains the queue in Room (see "Downloads in the background"
 * in docs/ARCHITECTURE.md). It has no input data at all. Drained is success; a retry or a stop is retry, and WorkManager
 * runs it again when its constraints hold. Android 15's 6-hour `dataSync` limit (a stop with
 * `STOP_REASON_FOREGROUND_SERVICE_TIMEOUT`) is said as [QueueStatus.PausedByTimeLimit]; the app kicks again when it next comes
 * to the foreground.
 */
internal class DownloadWorker(context: Context, params: WorkerParameters, private val parts: Parts) : CoroutineWorker(context, params) {
    /** [report]: the scheduler's status; [readySince]: downloads completed since an epoch time; [mobileData]: the setting. */
    class Parts(
        val queue: QueueDrain,
        val notifications: DownloadNotifications,
        val report: (QueueStatus) -> Unit,
        val readySince: suspend (Long) -> Int,
        val mobileData: () -> Boolean,
        val now: () -> Long,
    )

    /** The "Downloading" notice is up (it says the same for every item, so it is set once per run). */
    private var shown = false

    override suspend fun doWork(): Result {
        parts.notifications.ensureChannel()
        parts.notifications.clearWaiting()
        foreground(getForegroundInfo())
        val started = parts.now()
        val run = try {
            parts.queue.drain({ isStopped }) { status -> onStatus(status) }
        } catch (e: CancellationException) {
            stopped()
            throw e
        } catch (e: Exception) { // a full or failing database, say: try again later rather than stop the queue for good
            AppLog.w(TAG, "downloads drain failed: ${e.javaClass.simpleName}")
            parts.report(QueueStatus.Idle)
            return Result.retry()
        }
        return when (run) {
            QueueRun.Drained -> {
                parts.report(QueueStatus.Idle)
                if (parts.readySince(started) > 0) parts.notifications.finished()
                Result.success()
            }
            is QueueRun.Retry -> {
                waiting(QueueStatus.WaitingForConnection)
                Result.retry()
            }
            QueueRun.Stopped -> {
                stopped()
                Result.retry()
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        ForegroundInfo(DownloadNotifications.ID, parts.notifications.starting(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private suspend fun onStatus(status: QueueStatus) {
        parts.report(status)
        if (status !is QueueStatus.Running || shown) return
        shown = true
        foreground(ForegroundInfo(DownloadNotifications.ID, parts.notifications.running(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC))
    }

    /** Why it stopped, said plainly: the 6-hour limit, or Wi-Fi (or any connection) gone. */
    private fun stopped() {
        when (stopReason) {
            WorkInfo.STOP_REASON_FOREGROUND_SERVICE_TIMEOUT -> waiting(QueueStatus.PausedByTimeLimit)
            WorkInfo.STOP_REASON_CONSTRAINT_CONNECTIVITY -> waiting(if (parts.mobileData()) QueueStatus.WaitingForConnection else QueueStatus.WaitingForWifi)
            else -> Unit
        }
    }

    private fun waiting(status: QueueStatus) {
        parts.report(status)
        parts.notifications.showWaiting(status)
    }

    /** Best effort: a refused foreground start (the app in the background on Android 12+) still lets the work run. */
    private suspend fun foreground(info: ForegroundInfo) = try {
        setForeground(info)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.w(TAG, "downloads not shown in the foreground", e)
    }

    private companion object {
        const val TAG = "DownloadWorker"
    }
}
