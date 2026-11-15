package app.shoalwatch.data.downloads

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters

/**
 * WorkManager's worker factory (set in `ShoalwatchApp`'s `Configuration.Provider`): builds [DownloadWorker] with the graph's
 * queue and notices, without reflection on the graph. Anything else is null, and WorkManager builds its own workers as usual.
 */
internal class ShoalwatchWorkerFactory(private val parts: () -> DownloadWorker.Parts) : WorkerFactory() {
    override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker? =
        if (workerClassName == DownloadWorker::class.java.name) DownloadWorker(appContext, workerParameters, parts()) else null
}
