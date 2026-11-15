package app.shoalwatch.data.downloads

import app.shoalwatch.core.AppError
import app.shoalwatch.core.AppLog
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.db.downloads.DownloadStore
import app.shoalwatch.data.db.downloads.OfflineLibraryDao
import app.shoalwatch.data.db.downloads.OfflineRow
import app.shoalwatch.data.db.downloads.RemoveOutcome
import app.shoalwatch.data.db.downloads.toEntry
import app.shoalwatch.data.db.downloads.toModel
import app.shoalwatch.data.db.library.ItemDao
import app.shoalwatch.data.db.library.toModel
import app.shoalwatch.data.servers.JellyfinId
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Implements [Downloads] over [DownloadStore] and [OfflineLibraryDao] (see "Downloads seam" in docs/ARCHITECTURE.md). Pins are
 * written by the store under the library's write lock (AppGraph hands it one). Actions run in [scope], the app's, so they finish
 * even if the screen goes; their failures are AppError (a database failure is UNKNOWN). Flows never throw.
 */
class DownloadRepository internal constructor(
    private val store: DownloadStore,
    private val offline: OfflineLibraryDao,
    private val items: ItemDao,
    private val planner: DownloadPlanner,
    private val scheduler: DownloadScheduler,
    private val prefs: DownloadPrefs,
    private val volumes: Volumes,
    private val paths: DownloadPaths,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : Downloads {
    override fun library(s: Session): Flow<OfflineLibrary> = guarded(EMPTY_LIBRARY) {
        combine(offline.observeInProgress(s.serverId, s.userId), offline.observeFilms(s.serverId, s.userId), offline.observeSeries(s.serverId, s.userId)) { running, films, series ->
            val mounted = mounted()
            OfflineLibrary(running.map { it.entry(mounted) }, films.map { it.entry(mounted) }, series.map { it.toModel() })
        }
    }

    override fun series(s: Session, seriesId: String): Flow<OfflineSeries> {
        if (!isId(seriesId)) return flowOf(EMPTY_SERIES)
        return guarded(EMPTY_SERIES) {
            combine(
                offline.observeSeries(s.serverId, s.userId),
                offline.observeSeasons(s.serverId, s.userId, seriesId),
                offline.observeSeriesEpisodes(s.serverId, s.userId, seriesId),
            ) { all, seasonRows, episodeRows ->
                val mounted = mounted()
                val episodes = episodeRows.map { it.entry(mounted) }
                val seasons = seasonRows.map { it.toModel() }
                val bySeason = seasons.map { season ->
                    OfflineSeason(season, season.item.indexNumber, episodes.filter { it.item.item.seasonId == season.item.id })
                }.filter { it.episodes.isNotEmpty() }
                val known = seasons.map { it.item.id }.toSet()
                val loose = episodes.filter { it.item.item.seasonId == null || it.item.item.seasonId !in known }
                OfflineSeries(
                    series = all.firstOrNull { it.item.itemId == seriesId }?.toModel()?.series,
                    seasons = bySeason + if (loose.isEmpty()) emptyList() else listOf(OfflineSeason(null, null, loose)),
                )
            }
        }
    }

    override fun status(s: Session, itemId: String): Flow<DownloadInfo?> {
        if (!isId(itemId)) return flowOf(null)
        return guarded(null) { offline.observeOne(s.serverId, s.userId, itemId).map { row -> row?.entry(mounted())?.download } }
    }

    override fun seriesStatus(s: Session, seriesId: String): Flow<Map<String, DownloadInfo>> {
        if (!isId(seriesId)) return flowOf(emptyMap())
        return guarded(emptyMap()) {
            offline.observeForSeries(s.serverId, s.userId, seriesId).map { rows -> val m = mounted(); rows.associate { it.download.itemId to it.entry(m).download } }
        }
    }

    override val queue: StateFlow<QueueStatus> get() = scheduler.status

    override suspend fun prepare(s: Session, target: DownloadTarget, quality: DownloadQuality): DownloadPlan = action { planner.plan(s, target, quality) }

    override suspend fun start(s: Session, plan: DownloadPlan) {
        require(plan.session == s) { "A plan is for its own session." }
        action {
            val news = plan.items.mapNotNull { planned ->
                // As stored now and visible to [s]: an item the server has since hidden from this profile is skipped.
                val item = items.getItem(s.serverId, s.userId, planned.itemId)?.toModel()?.item ?: return@mapNotNull null
                DownloadStore.NewDownload(
                    item.id, item.kind, item.seriesId, item.seasonId, planned.quality, plan.storage, plan.volumeId, item.runtimeMs, null,
                    confirmed = planned.confirmed, shareOnly = planned.shareOnly,
                )
            }
            val added = store.add(s, news)
            if (added.values.any { it == DownloadStore.Added.Queued }) scheduler.kick()
        }
    }

    override suspend fun pause(s: Session, itemId: String) = action { store.setPaused(s, itemId, true); Unit }

    override suspend fun resume(s: Session, itemId: String) = action { if (store.setPaused(s, itemId, false)) scheduler.kick() }

    override suspend fun pauseAll(s: Session) = action { store.owned(s).forEach { store.setPaused(s, it, true) } }

    override suspend fun resumeAll(s: Session) = action {
        var any = false
        store.owned(s).forEach { if (store.setPaused(s, it, false)) any = true }
        if (any) scheduler.kick()
    }

    override suspend fun retry(s: Session, itemId: String) = action { if (store.retry(s, itemId)) scheduler.kick() }

    override suspend fun remove(s: Session, itemId: String): RemoveOutcome = action {
        val outcome = store.removeOwner(s, itemId)
        if (outcome is RemoveOutcome.Deleted) deleteFiles(paths, outcome.key)
        outcome
    }

    override val settings: StateFlow<DownloadSettings> get() = prefs.settings

    override fun storageChoices(): List<StorageChoice> = volumes.choices()

    override fun setMobileData(allowed: Boolean) = prefs.setMobileData(allowed) // the scheduler replaces the work (watchSettings)

    override fun setStorage(kind: StorageKind, volumeId: String?) = prefs.setStorage(kind, volumeId)

    override fun setDefaultQuality(q: DownloadQuality?) = prefs.setDefaultQuality(q)

    override fun sharing(serverId: String): Flow<Boolean> {
        if (!isId(serverId)) return flowOf(false)
        return guarded(false) { store.observeSharing(serverId) }
    }

    override suspend fun setSharing(s: Session, on: Boolean) = action { store.setSharing(s.serverId, on) }

    /** Mounted card ids, read once per emission (a copy on a card that is out is shown, but not as available). */
    private fun mounted(): Set<String> = try {
        volumes.choices().filter { it.kind == StorageKind.REMOVABLE && it.available }.mapNotNull { it.volumeId }.toSet()
    } catch (e: Exception) {
        emptySet()
    }

    private fun OfflineRow.entry(mounted: Set<String>) =
        toEntry(available = download.storage == StorageKind.INTERNAL || download.volumeId in mounted)

    private fun <T> guarded(empty: T, flow: () -> Flow<T>): Flow<T> = try {
        flow().catch { e -> if (e is CancellationException) throw e; AppLog.w(TAG, "downloads read failed", e); emit(empty) }.flowOn(io)
    } catch (e: Exception) {
        flowOf(empty)
    }

    /** In the app scope on [io]; anything that is not an AppError is UNKNOWN. */
    private suspend fun <T> action(block: suspend () -> T): T = scope.async(io) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError) {
            throw e
        } catch (e: IllegalArgumentException) {
            throw e
        } catch (e: Exception) {
            throw AppError.of(AppError.Kind.UNKNOWN, e)
        }
    }.await()

    private fun isId(id: String) = JellyfinId.parseOrNull(id)?.value == id

    internal companion object {
        const val TAG = "Downloads"
        val EMPTY_LIBRARY = OfflineLibrary(emptyList(), emptyList(), emptyList())
        val EMPTY_SERIES = OfflineSeries(null, emptyList())

        /** The item's folder on every mounted root (it may be on either; a card that is out is swept when it comes back). */
        fun deleteFiles(paths: DownloadPaths, key: DownloadKey) {
            for (server in paths.serverFolders(key.serverId)) paths.deleteInside(File(server, key.itemId))
        }
    }
}
