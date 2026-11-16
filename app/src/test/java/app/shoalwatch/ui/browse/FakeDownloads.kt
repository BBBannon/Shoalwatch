package app.shoalwatch.ui.browse

import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.db.downloads.RemoveOutcome
import app.shoalwatch.data.downloads.DownloadInfo
import app.shoalwatch.data.downloads.DownloadPlan
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.DownloadSettings
import app.shoalwatch.data.downloads.DownloadTarget
import app.shoalwatch.data.downloads.Downloads
import app.shoalwatch.data.downloads.OfflineLibrary
import app.shoalwatch.data.downloads.OfflineSeries
import app.shoalwatch.data.downloads.QueueStatus
import app.shoalwatch.data.downloads.StorageChoice
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * A [Downloads] the screen tests drive by hand (the plan's `FakeDownloads`): flows per session the test sets, every action
 * recorded as text (`pause:<id>` and so on), and [plans] answered by [prepare] or [prepareFailure] thrown.
 */
class FakeDownloads : Downloads {
    val libraries = mutableMapOf<Session, MutableStateFlow<OfflineLibrary>>()
    val serieses = mutableMapOf<Pair<Session, String>, MutableStateFlow<OfflineSeries>>()
    val statuses = mutableMapOf<Pair<Session, String>, MutableStateFlow<DownloadInfo?>>()
    val sharingFlows = mutableMapOf<String, MutableStateFlow<Boolean>>()
    override val queue = MutableStateFlow<QueueStatus>(QueueStatus.Idle)
    override val settings = MutableStateFlow(DownloadSettings())
    val calls = mutableListOf<String>()
    var plans: (Session, DownloadTarget, DownloadQuality) -> DownloadPlan = { _, _, _ -> error("no plan set") }
    var prepareFailure: Throwable? = null
    var removeOutcome: (Session, String) -> RemoveOutcome = { s, id -> RemoveOutcome.Deleted(DownloadKey(s.serverId, id)) }
    var choices = listOf(StorageChoice(StorageKind.INTERNAL, null, 1L shl 34, true))

    fun libraryFlow(s: Session) = libraries.getOrPut(s) { MutableStateFlow(OfflineLibrary(emptyList(), emptyList(), emptyList())) }
    fun seriesFlow(s: Session, seriesId: String) = serieses.getOrPut(s to seriesId) { MutableStateFlow(OfflineSeries(null, emptyList())) }
    fun statusFlow(s: Session, itemId: String) = statuses.getOrPut(s to itemId) { MutableStateFlow(null) }

    override fun library(s: Session): Flow<OfflineLibrary> = libraryFlow(s)
    override fun series(s: Session, seriesId: String): Flow<OfflineSeries> = seriesFlow(s, seriesId)
    override fun status(s: Session, itemId: String): Flow<DownloadInfo?> = statusFlow(s, itemId)
    override fun seriesStatus(s: Session, seriesId: String): Flow<Map<String, DownloadInfo>> =
        seriesFlow(s, seriesId).map { series -> series.seasons.flatMap { it.episodes }.associate { it.download.itemId to it.download } }

    override suspend fun prepare(s: Session, target: DownloadTarget, quality: DownloadQuality): DownloadPlan {
        calls += "prepare"
        prepareFailure?.let { throw it }
        return plans(s, target, quality)
    }

    override suspend fun start(s: Session, plan: DownloadPlan) { require(plan.session == s) { "A plan is for its own session." }; calls += "start:${plan.items.size}" }
    override suspend fun pause(s: Session, itemId: String) { calls += "pause:$itemId" }
    override suspend fun resume(s: Session, itemId: String) { calls += "resume:$itemId" }
    override suspend fun pauseAll(s: Session) { calls += "pauseAll" }
    override suspend fun resumeAll(s: Session) { calls += "resumeAll" }
    override suspend fun retry(s: Session, itemId: String) { calls += "retry:$itemId" }
    override suspend fun remove(s: Session, itemId: String): RemoveOutcome { calls += "remove:$itemId"; return removeOutcome(s, itemId) }
    override fun storageChoices(): List<StorageChoice> = choices
    override fun setMobileData(allowed: Boolean) { settings.value = settings.value.copy(mobileData = allowed) }
    override fun setStorage(kind: StorageKind, volumeId: String?) { settings.value = settings.value.copy(storage = kind, volumeId = volumeId) }
    override fun setDefaultQuality(q: DownloadQuality?) { settings.value = settings.value.copy(defaultQuality = q) }
    override fun sharing(serverId: String): Flow<Boolean> = sharingFlows.getOrPut(serverId) { MutableStateFlow(false) }
    override suspend fun setSharing(s: Session, on: Boolean) { calls += "sharing:$on"; sharingFlows.getOrPut(s.serverId) { MutableStateFlow(false) }.value = on }
}
