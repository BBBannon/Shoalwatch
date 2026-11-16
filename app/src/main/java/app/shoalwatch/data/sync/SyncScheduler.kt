package app.shoalwatch.data.sync

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import app.shoalwatch.core.AppLog
import app.shoalwatch.data.servers.Session
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * When sending happens (see "Sync outbox" in docs/ARCHITECTURE.md): in this process straight after each change, at start-up,
 * after a sign-in and each time a server with rows comes online; and by one unique WorkManager job, `shoalwatch-sync`, so a
 * change made just before the app is closed is still sent. [workManager] is asked for only when a job is scheduled, so nothing
 * starts WorkManager while the outbox is empty.
 */
class SyncScheduler internal constructor(
    private val workManager: () -> WorkManager,
    private val sender: SyncSender,
    private val outbox: OutboxStore,
    private val online: OnlineEvents,
    private val scope: CoroutineScope,
) {
    internal constructor(workManager: WorkManager, sender: SyncSender, outbox: OutboxStore, online: OnlineEvents, scope: CoroutineScope) :
        this({ workManager }, sender, outbox, online, scope)

    /** A test seam: counts each cameOnline event once it is fully handled. */
    internal val onlineHandled = AtomicInteger()

    /**
     * After every enqueue (its row already committed): kick [s] in process AND replace the job. REPLACE is safe: the worker only
     * awaits `sender.drainAll()`, which runs in the app scope, so replacing the job cancels a wait, never a send.
     */
    fun changed(s: Session) {
        sender.kick(s)
        schedule()
    }

    /**
     * `ShoalwatchApp.onCreate`: kick every profile with rows and schedule the job if there are any, then kick a server's profiles
     * each time it comes online. Returns the first part (for tests); the watch goes on in [scope].
     */
    fun start(): Job {
        scope.launch {
            online.cameOnline.collect { serverId ->
                // After a timeout the re-probe's Online is no reconnection: held, with a back-off (a change, start and sign-in never are).
                if (!sender.holdsReconnection(serverId)) withWork { it.serverId == serverId }.forEach(sender::kick)
                onlineHandled.incrementAndGet()
            }
        }
        return scope.launch {
            val sessions = withWork { true }
            sessions.forEach(sender::kick)
            if (sessions.isNotEmpty()) schedule()
        }
    }

    /** The store hook after a sign-in: that profile's rows can go now. */
    fun signedIn(serverId: String, userId: String) {
        val s = Session(serverId, userId)
        scope.launch {
            val waiting = try {
                outbox.waiting(s)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLog.w(TAG, "Reading the outbox failed", e)
                return@launch
            }
            if (waiting > 0) changed(s)
        }
    }

    private suspend fun withWork(keep: (Session) -> Boolean): List<Session> = try {
        outbox.accountsWithWork().filter(keep)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        AppLog.w(TAG, "Reading the outbox failed", e)
        emptyList()
    }

    /** A failure to reach WorkManager never fails the change: the next start, reconnection or change sends it. */
    private fun schedule() {
        try {
            workManager().enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request())
        } catch (e: Exception) {
            AppLog.w(TAG, "Scheduling the sync job failed", e)
        }
    }

    companion object {
        const val WORK_NAME = "shoalwatch-sync"
        private const val TAG = "SyncScheduler"
        private const val BACKOFF_SECONDS = 30L

        /** Any network (small requests; a home-only address simply waits for home), exponential from 30 s, no input data, not expedited. */
        internal fun request(): OneTimeWorkRequest = OneTimeWorkRequest.Builder(SyncWorker::class.java)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
    }
}
