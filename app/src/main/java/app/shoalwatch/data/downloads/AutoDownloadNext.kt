package app.shoalwatch.data.downloads

import app.shoalwatch.core.AppError
import app.shoalwatch.core.AppLog
import app.shoalwatch.data.db.downloads.AutoNextRequestEntity
import app.shoalwatch.data.db.downloads.HousekeepingDao
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.sync.OnlineEvents
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Auto-download next (see "Auto-download next" in docs/ARCHITECTURE.md). Per profile, off by default: after a downloaded episode
 * is finished or released, keep the next [ProfileSettings.autoNextCount] unwatched episodes of the show on the phone. A durable
 * request (`auto_next_requests`) is written first, so nothing is lost offline or across a restart; it is run now, at the next
 * start, and whenever its server comes online. SAFEGUARDING: the anchor must be a download [Session] may play, and the plan is
 * [Downloads.prepare]'s (only items the server shows this profile, and its own CanDownload decides).
 * [metered]: the active network is metered (a failure counts as metered).
 */
internal class AutoDownloadNext(
    private val dao: HousekeepingDao,
    private val downloads: Downloads,
    private val prefs: DownloadPrefs,
    private val metered: () -> Boolean,
    private val online: OnlineEvents,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val running = Mutex()

    /** (server, user, item) anchors already remembered in this process: a second report of the same watch is ignored. */
    private val seen = object : LinkedHashMap<Triple<String, String, String>, Unit>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Triple<String, String, String>, Unit>?) = size > SEEN_LIMIT
    }

    /** [itemId] (an episode this profile had downloaded) was finished or released: remember the series for [s], then try. */
    fun onWatched(s: Session, itemId: String): Job = scope.launch(io) {
        quietly { remember(s, itemId) }
        runPending(s)
    }

    /**
     * Writes the request while the anchor's download can still be read (before a release deletes it), so its quality is kept.
     * Nothing while the profile's setting is Off, for a repeat of an anchor already remembered, or for anything [s] may not play.
     */
    suspend fun remember(s: Session, itemId: String) {
        val key = Triple(s.serverId, s.userId, itemId)
        if (synchronized(seen) { key in seen }) return
        if ((dao.profile(s.serverId, s.userId)?.autoNextCount ?: 0) <= 0) return
        val anchor = dao.playable(s.serverId, s.userId, itemId) ?: return
        val seriesId = anchor.seriesId
        if (anchor.kind != ItemKind.EPISODE || seriesId == null) return
        dao.upsertAutoNext(AutoNextRequestEntity(s.serverId, s.userId, seriesId, itemId, anchor.quality, now(), tries = 0, blockedAt = null))
        synchronized(seen) { seen[key] = Unit }
    }

    /** For every waiting request of [s]: plan and start, or keep it for later. Never throws. */
    suspend fun runPending(s: Session) {
        quietly { running.withLock { for (request in dao.autoNextRequests(s.serverId, s.userId)) runOne(s, request) } }
    }

    /** ShoalwatchApp.onCreate: run every waiting request once, and again whenever its server comes online. */
    fun start() {
        scope.launch(io) {
            val sessions = quietly { dao.allAutoNextRequests().map { Session(it.serverId, it.userId) }.distinct() } ?: emptyList()
            for (s in sessions) runPending(s)
        }
        scope.launch(io) {
            online.cameOnline.collect { serverId ->
                val sessions = quietly { dao.allAutoNextRequests().filter { it.serverId == serverId }.map { Session(it.serverId, it.userId) }.distinct() } ?: emptyList()
                for (s in sessions) runPending(s)
            }
        }
    }

    private suspend fun runOne(s: Session, r: AutoNextRequestEntity) {
        val count = dao.profile(s.serverId, s.userId)?.autoNextCount ?: 0
        if (count !in ALLOWED || count == 0) return done(r) // turned Off since: nothing to keep
        val settings = prefs.settings.value
        if (!settings.mobileData && isMetered()) return // the next Wi-Fi connection runs it (it comes online again)
        try {
            val plan = downloads.prepare(s, DownloadTarget.NextUnwatched(r.seriesId, r.anchorItemId, count), settings.defaultQuality ?: r.quality)
            if (plan.items.isEmpty()) return done(r)
            if (!plan.enoughSpace) {
                // No partial batch: nothing is started; Storage says so, and the next start or trigger tries again.
                dao.updateAutoNext(r.serverId, r.userId, r.seriesId, r.anchorItemId, r.requestedAt, r.tries, now())
                return
            }
            downloads.start(s, plan)
            done(r)
        } catch (e: AppError) {
            if (e.kind in KEEP) return
            AppLog.w(TAG, "next episodes not planned: ${e.kind}")
            failed(r)
        } catch (e: IllegalArgumentException) {
            failed(r)
        }
    }

    private suspend fun done(r: AutoNextRequestEntity) {
        dao.deleteAutoNext(r.serverId, r.userId, r.seriesId, r.anchorItemId, r.requestedAt)
    }

    private suspend fun failed(r: AutoNextRequestEntity) {
        val tries = r.tries + 1
        if (tries >= MAX_TRIES) done(r) else dao.updateAutoNext(r.serverId, r.userId, r.seriesId, r.anchorItemId, r.requestedAt, tries, r.blockedAt)
    }

    private fun isMetered(): Boolean = try { metered() } catch (e: RuntimeException) { true }

    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.w(TAG, "auto-download next failed", e)
        null
    }

    companion object {
        const val MAX_TRIES = 5
        val ALLOWED = setOf(0, 1, 2, 3, 5)
        private const val TAG = "AutoNext"
        private const val SEEN_LIMIT = 256

        /** Kept for later: the server could not be reached, or the profile is signed out for now. */
        private val KEEP = setOf(AppError.Kind.OFFLINE, AppError.Kind.NETWORK, AppError.Kind.TIMEOUT, AppError.Kind.NOT_SIGNED_IN)
    }
}
