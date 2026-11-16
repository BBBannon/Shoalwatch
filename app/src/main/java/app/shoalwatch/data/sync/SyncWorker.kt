package app.shoalwatch.data.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * The `shoalwatch-sync` job (built by `ShoalwatchWorkerFactory`): it only awaits [SyncSender.drainAll], which runs in the app
 * scope, so stopping or replacing the job cancels a wait, never a send. No input data, no notification: the outbox is in Room.
 */
internal class SyncWorker(context: Context, params: WorkerParameters, private val sender: SyncSender) : CoroutineWorker(context, params) {
    /** drainAll: EMPTY, WAITING_FOR_SIGN_IN → success (a sign-in kicks again); WAITING_FOR_CONNECTION, BACKING_OFF, STOPPED → retry. */
    override suspend fun doWork(): Result = when (sender.drainAll()) {
        DrainResult.EMPTY, DrainResult.WAITING_FOR_SIGN_IN -> Result.success()
        DrainResult.WAITING_FOR_CONNECTION, DrainResult.BACKING_OFF, DrainResult.STOPPED -> Result.retry()
    }
}
