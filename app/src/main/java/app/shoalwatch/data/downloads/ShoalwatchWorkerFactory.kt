package app.shoalwatch.data.downloads

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import app.shoalwatch.data.sync.SyncSender
import app.shoalwatch.data.sync.SyncWorker

/**
 * WorkManager's worker factory (set in `ShoalwatchApp`'s `Configuration.Provider`): builds [DownloadWorker] with the graph's
 * queue and notices, and [SyncWorker] with the graph's sender, without reflection on the graph. Anything else is null, and
 * WorkManager builds its own workers as usual.
 */
internal class ShoalwatchWorkerFactory(private val parts: () -> DownloadWorker.Parts, private val sync: () -> SyncSender) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        when (workerClassName) {
            DownloadWorker::class.java.name -> DownloadWorker(appContext, workerParameters, parts())
            SyncWorker::class.java.name -> SyncWorker(appContext, workerParameters, sync())
            else -> null
        }
}
