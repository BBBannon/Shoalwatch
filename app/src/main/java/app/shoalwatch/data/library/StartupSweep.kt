package app.shoalwatch.data.library

import app.shoalwatch.core.AppLog
import app.shoalwatch.core.MonotonicClock
import app.shoalwatch.data.db.library.LibraryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The start-up sweep: clears the `pendingSyncAt` markers that a PREVIOUS process left behind (the app died mid-action), because
 * a marker with no live action would make its row ignore the server's copy for as long as it stands. The local values stay;
 * each user who really had a leftover has their lists aged so the server's copy arrives promptly and decides. In this
 * milestone an unconfirmed change may therefore be reverted. **Milestone 5 owns recovery:** a marker with no live action will
 * then mean "send this change", not "discard it", and this sweep (like the tidy-up queue's idle clean-up) must be revisited.
 *
 * What it clears is exactly a SNAPSHOT of the rows that had a marker when it first managed to look (one query, the first
 * thing it does, before any entry point lets an action run), minus any row this process has touched since: so a retry, which can
 * run long after the first attempt, can never clear the marker of an action that is waiting on the server right now. If the
 * snapshot query itself fails, the next attempt takes it then, and the same exclusion applies.
 *
 * [ensure] is called by every entry point before it reads freshness or writes anything (list flows, refreshes and "load
 * more", search, and the tidy-up actions through `LibraryRepository.writeGated`). It takes the same write gate as every list
 * write. If an attempt fails (the database is unwell) it is logged (the exception's type only) and tried again on the next call at
 * least [RETRY_AFTER_MS] later by the [monotonic] clock (not the wall clock, which can be set back), at most [MAX_ATTEMPTS]
 * times in all; until one succeeds a stuck marker keeps its row ignoring the server.
 */
internal class StartupSweep(
    private val store: LibraryStore,
    private val writeGate: Mutex,
    private val monotonic: MonotonicClock,
) {
    private val lock = Mutex()

    @Volatile private var done = false
    private var attempts = 0
    private var lastAttemptAt = 0L
    private var snapshot: Set<LibraryStore.PendingKey>? = null

    suspend fun ensure() {
        if (done) return
        lock.withLock {
            if (done || (attempts > 0 && (attempts >= MAX_ATTEMPTS || monotonic.nowMs() - lastAttemptAt < RETRY_AFTER_MS))) return
            attempts++
            lastAttemptAt = monotonic.nowMs()
            try {
                val leftovers = snapshot ?: store.pendingKeys().also { snapshot = it }
                writeGate.withLock { withContext(NonCancellable) { store.clearLeftovers(leftovers, STALE_SHIFT_MS) } }
                done = true
            } catch (e: CancellationException) {
                attempts-- // the caller left while waiting for the gate: that was not an attempt
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "Start-up sweep failed (${e.javaClass.simpleName}), attempt $attempts of $MAX_ATTEMPTS")
            }
        }
    }

    private companion object {
        const val TAG = "LibrarySweep"
        const val MAX_ATTEMPTS = 5
        const val RETRY_AFTER_MS = 30_000L

        /** More than the longest freshness window (15 minutes), so an aged list always reads as stale, yet far under the 30 days after which a list is cleared out. */
        const val STALE_SHIFT_MS = 60L * 60 * 1000
    }
}
