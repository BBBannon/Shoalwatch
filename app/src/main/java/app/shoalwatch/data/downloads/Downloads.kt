package app.shoalwatch.data.downloads

import app.shoalwatch.data.db.downloads.RemoveOutcome
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * What screens use. Every read is for one Session and shows only what that profile may see (see "Session and sharing rule"
 * in docs/ARCHITECTURE.md): its own `visible_items` record for the item (and its season and series on the series screens),
 * and either its own download or, with the server's sharing on, a COMPLETE one. Flows never throw: a failure or a malformed
 * id is an empty value. Actions throw `AppError` and finish even if the screen goes.
 */
interface Downloads {
    fun library(s: Session): Flow<OfflineLibrary>
    fun series(s: Session, seriesId: String): Flow<OfflineSeries>
    fun status(s: Session, itemId: String): Flow<DownloadInfo?>
    fun seriesStatus(s: Session, seriesId: String): Flow<Map<String, DownloadInfo>>
    val queue: StateFlow<QueueStatus>

    /** Online. Throws AppError (OFFLINE and the rest, unchanged). */
    suspend fun prepare(s: Session, target: DownloadTarget, quality: DownloadQuality): DownloadPlan

    /** Adds the plan's items for [s] (the plan must be [s]'s; anything no longer visible is skipped) and kicks the worker. */
    suspend fun start(s: Session, plan: DownloadPlan)

    /** Owners only, as every change below: another profile's download is left as it is. */
    suspend fun pause(s: Session, itemId: String)
    suspend fun resume(s: Session, itemId: String)
    suspend fun pauseAll(s: Session)
    suspend fun resumeAll(s: Session)
    suspend fun retry(s: Session, itemId: String)

    /** Removes [s]'s ownership; the files go when no owner is left ([RemoveOutcome.Deleted]). */
    suspend fun remove(s: Session, itemId: String): RemoveOutcome

    val settings: StateFlow<DownloadSettings>
    fun storageChoices(): List<StorageChoice>
    fun setMobileData(allowed: Boolean)
    fun setStorage(kind: StorageKind, volumeId: String?)
    fun setDefaultQuality(q: DownloadQuality?)

    /** "Share downloads between profiles" for the server (off until set). */
    fun sharing(serverId: String): Flow<Boolean>

    /** Any signed-in profile on the server may change it; each profile still sees only what its own account may. */
    suspend fun setSharing(s: Session, on: Boolean)
}
