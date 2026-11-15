package app.shoalwatch.data.library

import app.shoalwatch.core.AppLog
import app.shoalwatch.data.db.library.LibraryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The start-up sweep: clears EVERY `pendingSyncAt` marker (all servers and users; the local values stay), because a marker
 * with no live action means the app died mid-action, and left alone it would make that row ignore the server's copy for
 * as long as the marker stands. Each affected user's lists are aged so the server's copy arrives promptly and decides.
 * In this milestone an unconfirmed change may therefore be reverted. **Milestone 5 owns recovery:** a marker with no live
 * action will then mean "send this change", not "discard it".
 *
 * [ensure] is called by every entry point before it reads freshness or writes anything (list flows, refreshes and "load
 * more", search, and the tidy-up actions through `LibraryRepository.writeGated`), so it has always run before anything
 * could set a marker of its own. It takes the same write gate as every list write. If the attempt fails (the database is
 * unwell) it is logged (the exception's type only) and tried again on the next call at least [RETRY_AFTER_MS] later, at most
 * [MAX_ATTEMPTS] times in all; until one succeeds a stuck marker keeps its row ignoring the server, and the tidy-up queue's own
 * idle clean-up is the other thing that clears one.
 */
internal class StartupSweep(
    private val store: LibraryStore,
    private val writeGate: Mutex,
    private val clock: () -> Long,
) {
    private val lock = Mutex()

    @Volatile private var done = false
    private var attempts = 0
    private var lastAttemptAt = 0L

    suspend fun ensure() {
        if (done) return
        lock.withLock {
            if (done || (attempts > 0 && (attempts >= MAX_ATTEMPTS || clock() - lastAttemptAt < RETRY_AFTER_MS))) return
            attempts++
            lastAttemptAt = clock()
            try {
                writeGate.withLock { withContext(NonCancellable) { store.clearAllPending(STALE_SHIFT_MS) } }
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
