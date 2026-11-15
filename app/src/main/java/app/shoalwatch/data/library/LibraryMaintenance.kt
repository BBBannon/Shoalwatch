package app.shoalwatch.data.library

import android.content.Context
import app.shoalwatch.data.db.library.EvictionPolicy
import app.shoalwatch.data.db.library.LibraryStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** When eviction last ran, kept across restarts. Epoch milliseconds. */
interface MaintenanceLog {
    fun lastRun(): Long?
    fun record(atMs: Long)
}

/** One number in a private preferences file; reading and writing are synchronous, so only call them off the main thread. */
class PrefsMaintenanceLog(context: Context) : MaintenanceLog {
    private val prefs = context.applicationContext.getSharedPreferences("library_maintenance", Context.MODE_PRIVATE)

    override fun lastRun(): Long? = prefs.getLong(KEY, -1L).takeIf { it >= 0 }

    override fun record(atMs: Long) = prefs.edit().putLong(KEY, atMs).apply()

    private companion object {
        const val KEY = "last_eviction_ms"
    }
}

/**
 * Keeps the cache bounded: [LibraryStore.evict] with the default policy (lists not refreshed for 30 days, unreferenced
 * unpinned items older than 30 days, at most 20,000 unpinned items per server), at most once every 24 hours.
 * One schedule covers every server (eviction visits each in turn), so no server is evicted more often than that.
 *
 * [kick] is cheap and safe to call from any thread whenever the library is used: it launches the check in [scope], off
 * the main thread, and does nothing when a run is in progress or one happened within a day (a recorded run in the
 * future, from a clock that went back, does not count: it is due). The run holds [writeGate], the same lock every list
 * write takes, for one server at a time, so eviction never overlaps a refresh write and never blocks them for long. A failed run is not recorded, so the
 * next [kick] tries again.
 */
internal class LibraryMaintenance(
    private val store: LibraryStore,
    private val log: MaintenanceLog,
    private val clock: () -> Long,
    private val scope: CoroutineScope,
    private val context: CoroutineContext,
    private val writeGate: Mutex,
    private val every: Duration = Duration.ofHours(24),
) {
    private val busy = AtomicBoolean(false)

    @Volatile private var lastKnown: Long? = null

    private fun recent(last: Long, now: Long) = now - last in 0 until every.toMillis()

    fun kick() {
        lastKnown?.let { if (recent(it, clock())) return }
        if (!busy.compareAndSet(false, true)) return
        scope.launch(context) {
            try {
                val last = lastKnown ?: log.lastRun()
                val now = clock()
                if (last != null && recent(last, now)) {
                    lastKnown = last
                    return@launch
                }
                for (serverId in store.serverIds()) writeGate.withLock { store.evictServer(serverId, Instant.ofEpochMilli(now), EvictionPolicy()) }
                log.record(now)
                lastKnown = now
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Maintenance is best effort: a failure must never reach a screen. Not recorded, so it is tried again.
            } finally {
                busy.set(false)
            }
        }
    }
}
