package app.shoalwatch.data.sync

import app.shoalwatch.core.AppError
import app.shoalwatch.core.AppLog
import app.shoalwatch.data.db.sync.OutboxEntity
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.sync.api.SyncGateway
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** What this phone does when a row is done; implemented in data/library (Task 7). */
internal interface SyncLanding {
    /** The server has the change: the row goes, uncovered markers clear, lists the server recomputes are refreshed. */
    suspend fun confirmed(s: Session, seq: Long)

    /** The server's newer copy wins, or it refused the item: the row goes, markers clear, the item and lists are refreshed so the server's copy shows. */
    suspend fun overruled(s: Session, seq: Long)
}

/**
 * Sends each profile's outbox rows in `seq` order, once, and only as that profile (see "Sync outbox" in docs/ARCHITECTURE.md).
 * At most one drain runs per profile ([kick] joins it), at most [PARALLEL] in all. A row ends only by [SyncLanding] (the server
 * confirmed or overruled it), never for failing: a failure either stops the drain with the row left as it is, or defers the row.
 */
internal class SyncSender(
    private val outbox: OutboxStore,
    private val gateway: SyncGateway,
    private val landing: SyncLanding,
    private val hasToken: (Session) -> Boolean, // serverStore.cachedToken(serverId, userId) != null
    private val awaitWarmed: suspend () -> Unit, // serverStore.awaitWarmed
    private val scope: CoroutineScope, // the app scope
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** One joined drain: [result] never fails; [work] is the part [forget] cancels; [again]: a kick joined it, so look once more before ending. */
    private class Running(val result: Deferred<DrainResult>) {
        @Volatile var work: Deferred<DrainResult>? = null
        @Volatile var forgotten = false
        var again = false // under lock
    }

    private val lock = Any()
    private val running = HashMap<Session, Running>()
    private val profileLocks = HashMap<Session, Mutex>()
    private val permits = Semaphore(PARALLEL)

    /** A test seam: runs when a drain has found nothing more due, before it ends. */
    @Volatile internal var beforeIdle: suspend (Session) -> Unit = {}

    /** Starts a drain for [s] in [scope], or joins the one running. Never throws. */
    fun kick(s: Session): Deferred<DrainResult> = synchronized(lock) {
        running[s]?.let { it.again = true; return it.result }
        lateinit var entry: Running
        val result = scope.async(io, start = CoroutineStart.LAZY) {
            val work = async { profileLock(s).withLock { permits.withPermit { drain(s, entry) } } }
            entry.work = work
            if (entry.forgotten) work.cancel() // forget ran before [work] existed (Tasks 3-5 review M1)
            try {
                work.await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive() // the app scope itself is going
                if (hasToken(s)) DrainResult.STOPPED else DrainResult.WAITING_FOR_SIGN_IN // forgotten
            } finally {
                synchronized(lock) { if (running[s] === entry) running.remove(s) }
            }
        }
        entry = Running(result)
        running[s] = entry
        result.start()
        result
    }

    /** Every profile with rows, one drain each (in parallel, at most 4); the worst result. The worker awaits this. */
    suspend fun drainAll(): DrainResult {
        val sessions = try {
            outbox.accountsWithWork()
        } catch (e: Exception) {
            AppLog.w(TAG, "Reading the outbox failed", e)
            return DrainResult.STOPPED
        }
        return sessions.map(::kick).awaitAll().maxByOrNull { WORST.indexOf(it) } ?: DrainResult.EMPTY
    }

    /** Called by the store hooks: ends any wait for that account (or server) and forgets its joined drain. */
    fun forget(serverId: String, userId: String? = null) {
        val gone = synchronized(lock) {
            running.keys.filter { it.serverId == serverId && (userId == null || it.userId == userId) }.mapNotNull { running.remove(it) }
        }
        gone.forEach { it.forgotten = true; it.work?.cancel() }
    }

    private fun profileLock(s: Session) = synchronized(lock) { profileLocks.getOrPut(s) { Mutex() } }

    private suspend fun drain(s: Session, entry: Running): DrainResult = try {
        drainRows(s, entry)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        AppLog.w(TAG, "A drain failed", e)
        DrainResult.STOPPED
    }

    private suspend fun drainRows(s: Session, entry: Running): DrainResult {
        awaitWarmed()
        val done = HashSet<Long>()
        while (true) {
            if (!hasToken(s)) return DrainResult.WAITING_FOR_SIGN_IN
            val row = try {
                outbox.head(s, now())
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                AppLog.w(TAG, "Reading the outbox failed", e)
                return DrainResult.STOPPED
            }
            if (row == null) {
                beforeIdle(s)
                if (lookAgainOrLeave(s, entry)) continue
                return finish(s)
            }
            if (!done.add(row.seq)) {
                AppLog.w(TAG, "A landed outbox row was still there") // the landing did not take it: never loop on it
                return DrainResult.STOPPED
            }
            val stop = (send(s, row) as? Outcome.Stop)?.result ?: continue
            // A kick joined while this request was failing for want of a connection (a reconnection's kick): one more pass for it,
            // so the row goes now rather than at the job's back-off (Tasks 6-8 review M1). Each joined kick buys one pass at most.
            if (stop == DrainResult.WAITING_FOR_CONNECTION && takeAgain(entry)) done.remove(row.seq) else return stop
        }
    }

    private fun takeAgain(entry: Running): Boolean = synchronized(lock) { entry.again.also { entry.again = false } }

    /** A test seam: whether a drain for [s] can still be joined. */
    internal fun isDraining(s: Session): Boolean = synchronized(lock) { s in running }

    /**
     * Nothing more is due. One step under [lock]: if a kick joined this drain since it last looked, look again (its row may be
     * new); otherwise stop being joinable, so a kick from now on starts a new drain (which waits for this one) instead of joining
     * one that will never read its row (Tasks 3-5 review I1).
     */
    private fun lookAgainOrLeave(s: Session, entry: Running): Boolean = synchronized(lock) {
        if (entry.again) {
            entry.again = false
            true
        } else {
            if (running[s] === entry) running.remove(s)
            false
        }
    }

    private sealed interface Outcome {
        data object Landed : Outcome
        data object Deferred : Outcome
        data class Stop(val result: DrainResult) : Outcome
    }

    /** One row: read the server's copy if the rule needs it, decide, send at most one request, land it. */
    private suspend fun send(s: Session, row: OutboxEntity): Outcome {
        try {
            val server = if (ConflictRule.needsServerCopy(row)) {
                if (!hasToken(s)) return Outcome.Stop(DrainResult.WAITING_FOR_SIGN_IN)
                gateway.userData(s, row.itemId)
            } else {
                null
            }
            when (val step = ConflictRule.decide(row, server)) {
                Step.AlreadyThere -> landing.confirmed(s, row.seq)
                Step.ServerIsNewer -> landing.overruled(s, row.seq)
                else -> {
                    outbox.markSending(row.seq, (step as? Step.Update)?.body?.playCount?.takeIf { row.kind == ChangeKind.PLAY })
                    if (!hasToken(s)) return Outcome.Stop(DrainResult.WAITING_FOR_SIGN_IN)
                    request(s, row.itemId, step)
                    landing.confirmed(s, row.seq)
                }
            }
            return Outcome.Landed
        } catch (e: AppError) {
            return failed(s, row, e.kind)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.w(TAG, "Sending a change failed", e) // a local database failure, or anything else: by class only
            return Outcome.Stop(DrainResult.STOPPED)
        }
    }

    private suspend fun request(s: Session, itemId: String, step: Step) = when (step) {
        is Step.Update -> gateway.update(s, itemId, step.body)
        Step.MarkPlayed -> gateway.markPlayed(s, itemId)
        Step.MarkUnplayed -> gateway.markUnplayed(s, itemId)
        is Step.Favourite -> gateway.favourite(s, itemId, step.on)
        Step.AlreadyThere, Step.ServerIsNewer -> Unit
    }

    /** The failure table: the row stays unless the server answered about this item for this profile. */
    private suspend fun failed(s: Session, row: OutboxEntity, kind: AppError.Kind): Outcome = when (kind) {
        AppError.Kind.OFFLINE, AppError.Kind.NETWORK, AppError.Kind.TIMEOUT -> Outcome.Stop(DrainResult.WAITING_FOR_CONNECTION)
        AppError.Kind.SERVER_ERROR -> defer(row)
        AppError.Kind.UNAUTHORISED, AppError.Kind.NOT_SIGNED_IN -> Outcome.Stop(DrainResult.WAITING_FOR_SIGN_IN)
        AppError.Kind.FORBIDDEN -> overrule(s, row)
        AppError.Kind.SERVER_NOT_FOUND -> notFound(s, row)
        AppError.Kind.TLS_UNTRUSTED, AppError.Kind.TLS_PIN_CHANGED, AppError.Kind.CLIENT_CERT_REQUIRED, AppError.Kind.CLEARTEXT_BLOCKED ->
            Outcome.Stop(DrainResult.WAITING_FOR_CONNECTION)
        else -> {
            AppLog.w(TAG, "Sending a change failed: $kind")
            Outcome.Stop(DrainResult.STOPPED)
        }
    }

    /** Gone for this profile (the views answer): overruled. A redirect or a proxy: deferred like a 5xx, never dropped. */
    private suspend fun notFound(s: Session, row: OutboxEntity): Outcome {
        if (!hasToken(s)) return Outcome.Stop(DrainResult.WAITING_FOR_SIGN_IN)
        val gone = try {
            gateway.itemGone(s, row.itemId)
        } catch (e: AppError) {
            return if (e.kind == AppError.Kind.SERVER_NOT_FOUND) defer(row) else failed(s, row, e.kind)
        }
        return if (gone) overrule(s, row) else defer(row)
    }

    private suspend fun overrule(s: Session, row: OutboxEntity): Outcome {
        landing.overruled(s, row.seq)
        return Outcome.Landed
    }

    private suspend fun defer(row: OutboxEntity): Outcome {
        outbox.deferAfterServerError(row.seq)
        return Outcome.Deferred
    }

    /** Nothing is due: rows still waiting are deferred or stuck ones; none left means this profile is fully sent. */
    private suspend fun finish(s: Session): DrainResult = try {
        if (outbox.waiting(s) > 0) DrainResult.BACKING_OFF else DrainResult.EMPTY.also { outbox.touchSynced(s) }
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        AppLog.w(TAG, "Reading the outbox failed", e)
        DrainResult.STOPPED
    }

    private companion object {
        const val TAG = "SyncSender"
        const val PARALLEL = 4

        /** Least to most in need of another attempt: the worker acts on the worst. */
        val WORST = listOf(
            DrainResult.EMPTY, DrainResult.WAITING_FOR_SIGN_IN, DrainResult.BACKING_OFF, DrainResult.WAITING_FOR_CONNECTION, DrainResult.STOPPED,
        )
    }
}
