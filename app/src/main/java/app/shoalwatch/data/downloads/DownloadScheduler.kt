package app.shoalwatch.data.downloads

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The queue's one unique work, `shoalwatch-downloads`. [kick] enqueues it (KEEP: running work is left alone); REPLACE when the
 * network setting changed, so the new constraint applies. Wi-Fi only (`UNMETERED`) while "Use mobile data" is off, the
 * default (SPEC §3 row 10); any connection when it is on; never while storage is low. No input data: the queue is in Room.
 * [workManager] is asked for only at the first [kick], so nothing starts WorkManager before anything is queued.
 */
class DownloadScheduler internal constructor(private val workManager: () -> WorkManager, private val prefs: DownloadPrefs) {
    internal constructor(workManager: WorkManager, prefs: DownloadPrefs) : this({ workManager }, prefs)

    private val state = MutableStateFlow<QueueStatus>(QueueStatus.Idle)

    /** What the worker last said (Idle until it runs). */
    val status: StateFlow<QueueStatus> = state.asStateFlow()

    internal fun report(status: QueueStatus) { state.value = status }

    fun kick(replace: Boolean = false) {
        workManager().enqueueUniqueWork(NAME, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request(prefs.settings.value.mobileData))
    }

    /**
     * The app came to the front (its activity started): kick if anything is queued. This also resumes after Android's 6-hour
     * data-sync limit, which the platform resets then. Called from `MainActivity.onStart`, never from a process-wide
     * observer, so nothing outlives the app's own graph.
     */
    internal suspend fun inFront(anyQueued: suspend () -> Boolean) { if (anyQueued()) kick() }

    /** Replaces the work when "Use mobile data" changes while anything is queued, so the new constraint applies. */
    internal fun watchSettings(scope: CoroutineScope, anyQueued: suspend () -> Boolean): Job = scope.launch {
        prefs.settings.map { it.mobileData }.distinctUntilChanged().drop(1).collect { if (anyQueued()) kick(replace = true) }
    }

    companion object {
        const val NAME = "shoalwatch-downloads"
        private const val BACKOFF_SECONDS = 30L

        internal fun request(mobileData: Boolean): OneTimeWorkRequest = OneTimeWorkRequest.Builder(DownloadWorker::class.java)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(if (mobileData) NetworkType.CONNECTED else NetworkType.UNMETERED)
                    .setRequiresStorageNotLow(true)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}
