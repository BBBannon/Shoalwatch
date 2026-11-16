package app.shoalwatch.data.downloads

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkInfo
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
 * The queue's one unique work, `shoalwatch-downloads`. [kick] keeps a RUNNING worker as it is, and otherwise REPLACES the
 * work, so a request waiting out a long backoff (hours, after a night with the server off) starts again now (Tasks 11-12 review
 * I-3); when its state cannot be read it keeps it. [running] is a seam for tests. REPLACE always when the network setting
 * changed, so the new constraint applies. Wi-Fi only (`UNMETERED`) while "Use mobile data" is off, the
 * default (SPEC §3 row 10); any connection when it is on; never while storage is low. No input data: the queue is in Room.
 * [workManager] is asked for only at the first [kick], so nothing starts WorkManager before anything is queued.
 */
class DownloadScheduler internal constructor(
    private val workManager: () -> WorkManager,
    private val prefs: DownloadPrefs,
    private val running: (WorkManager) -> Boolean = { wm -> isRunning { wm.getWorkInfosForUniqueWork(NAME).get() } },
) {
    internal constructor(workManager: WorkManager, prefs: DownloadPrefs) : this({ workManager }, prefs)

    private val state = MutableStateFlow<QueueStatus>(QueueStatus.Idle)

    /** What the worker last said (Idle until it runs). */
    val status: StateFlow<QueueStatus> = state.asStateFlow()

    internal fun report(status: QueueStatus) { state.value = status }

    fun kick(replace: Boolean = false) {
        val wm = workManager()
        val policy = if (replace || !running(wm)) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        wm.enqueueUniqueWork(NAME, policy, request(prefs.settings.value.mobileData))
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

        /** Whether the unique work is RUNNING now; true (keep it) when that cannot be read. */
        internal fun isRunning(infos: () -> List<WorkInfo>): Boolean = try {
            infos().any { it.state == WorkInfo.State.RUNNING }
        } catch (e: Exception) {
            true
        }

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
