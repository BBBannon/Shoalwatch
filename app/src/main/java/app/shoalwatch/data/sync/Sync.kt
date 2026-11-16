package app.shoalwatch.data.sync

import app.shoalwatch.core.AppLog
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/** What screens use. Every read is for one Session. Flows never throw. */
interface Sync {
    fun status(s: Session): Flow<SyncStatus>

    /** How many of [s]'s changes are waiting; 0 when the outbox cannot be read. Never throws (except for cancellation). */
    suspend fun waiting(s: Session): Int

    /** Sends what it can for [s] within [timeoutMs]; returns how many are still waiting. Never throws. */
    suspend fun sendNow(s: Session, timeoutMs: Long = 10_000): Int
}

/**
 * [Sync] over the outbox and the sender. [signedIn] is `ServerStore.usableAccounts`: a profile with rows whose token is not loaded
 * "needs a sign-in". [sendNow] joins (or starts) the profile's one drain and stops WAITING for it at the timeout; the drain itself
 * runs on in the app scope, so a change that was being sent is not cut off half way.
 */
class SyncRepository internal constructor(
    private val outbox: OutboxStore,
    private val sender: SyncSender,
    private val signedIn: StateFlow<Set<Pair<String, String>>>,
) : Sync {
    override fun status(s: Session): Flow<SyncStatus> =
        outbox.status(s, signedIn.map { (s.serverId to s.userId) in it }.distinctUntilChanged()).distinctUntilChanged()

    override suspend fun waiting(s: Session): Int = try {
        outbox.waiting(s)
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive() // a real cancellation propagates; a closed database's own one does not
        AppLog.w(TAG, "Reading the outbox failed", e)
        0
    }

    override suspend fun sendNow(s: Session, timeoutMs: Long): Int {
        withTimeoutOrNull(timeoutMs) { sender.kick(s).await() } // the kick's result never fails; only this wait ends
        return waiting(s)
    }

    private companion object {
        const val TAG = "Sync"
    }
}
