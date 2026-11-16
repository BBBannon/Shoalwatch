package app.shoalwatch.data.downloads

import app.shoalwatch.core.AppError
import app.shoalwatch.core.AppLog
import kotlinx.coroutines.CancellationException
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.db.downloads.DownloadStore
import app.shoalwatch.data.playback.Resolved
import java.io.File

/* How one item's run in [DownloadQueue] flows: its outcome, the ends that cut it short, and what it carries. */

/** What a drain ends in. */
internal sealed interface QueueRun {
    data object Drained : QueueRun
    data class Retry(val kind: AppError.Kind) : QueueRun
    data object Stopped : QueueRun
}

/** The queue's pure rules for what the server said. */
internal object QueueFailures {
    /** A server call's error as this item's failure (Task 1's mapping), or null: a retry of that kind. */
    fun of(kind: AppError.Kind): DownloadFailure? = when (kind) {
        AppError.Kind.UNAUTHORISED, AppError.Kind.NOT_SIGNED_IN -> DownloadFailure.SIGN_IN_NEEDED
        AppError.Kind.FORBIDDEN, AppError.Kind.PLAYBACK_NOT_ALLOWED -> DownloadFailure.NOT_ALLOWED
        AppError.Kind.SERVER_NOT_FOUND -> DownloadFailure.GONE_FROM_SERVER
        AppError.Kind.NO_COMPATIBLE_STREAM -> DownloadFailure.NOT_PLAYABLE
        else -> null
    }

    /** The converted source's own runtime (bounded by the mapper), when positive (W-1). */
    fun sourceRuntime(resolved: Resolved): Long? = resolved.plan.runtimeMs?.takeIf { it > 0 }

    /** Runs a best-effort part of a download (its extras): a failure is logged by class and skipped. */
    suspend fun bestEffort(tag: String, block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { AppLog.w(tag, "download extras skipped: ${e.javaClass.simpleName}") }
    }
}

/** The queue's file chores that need no queue state. */
internal object QueueFiles {
    /** Called after a playback failure of a saved copy: re-reads it and marks it FAILED(DAMAGED) if the fingerprint differs. */
    suspend fun recheck(store: DownloadStore, paths: DownloadPaths, verifier: PlayableCheck, key: DownloadKey) {
        val row = store.get(key)?.takeIf { it.state == DownloadState.COMPLETE } ?: return
        val sha = row.sha256 ?: return
        val folder = paths.itemFolder(key, row.storage, row.volumeId) ?: return // the card is out: nothing to say yet
        val media = SavedMedia(key.serverId, key.itemId, folder, QueueSpace.entryOf(paths, row, folder) ?: return, row.container == QueueSpace.HLS, row.quality, row.mediaSourceId ?: return)
        if (!verifier.recheck(media, sha)) {
            store.updateIf(key, setOf(DownloadState.COMPLETE)) { it.copy(state = DownloadState.FAILED, failure = DownloadFailure.DAMAGED) }
            AppLog.w("DownloadQueue", "download damaged")
        }
    }

    /** The row's card is no longer mounted (a pulled card reads as no space or an I/O error). */
    suspend fun cardGone(store: DownloadStore, paths: DownloadPaths, key: DownloadKey): Boolean =
        store.get(key)?.let { it.storage == StorageKind.REMOVABLE && paths.itemFolder(key, it.storage, it.volumeId) == null } == true

    /** A file saved under another container (the server remuxed it since) never stays beside this one (review Minor 9). */
    fun dropOtherContainers(paths: DownloadPaths, folder: File, container: String) {
        for (ext in DownloadPaths.MEDIA_EXTENSIONS - container) {
            paths.mediaFile(folder, ext).takeIf { it.exists() }?.let(paths::deleteInside)
            paths.partFile(folder, ext).takeIf { it.exists() }?.let(paths::deleteInside)
        }
    }
}

/** What one item's run ends in: the next item, or the end of this drain. */
internal sealed interface QueueStep {
    data object Next : QueueStep
    data class Halt(val run: QueueRun) : QueueStep
}

/** Ends an item's run from anywhere in it (a fetcher only catches IOException, so these pass through). */
internal class QueueEnds(val step: QueueStep) : RuntimeException(null, null, false, false)

/** The row is gone (cancelled, account or server removed): its files are deleted. */
internal class QueueRowGone : RuntimeException(null, null, false, false)

/** The row left RUNNING meanwhile (paused): it is left as it is, with its part file and live conversion. */
internal class QueueMoved : RuntimeException(null, null, false, false)

internal class QueueJob(val key: DownloadKey, val position: Int, val of: Int, val report: suspend (QueueStatus) -> Unit, val stop: () -> Boolean) {
    suspend fun status(progress: Float?) = report(QueueStatus.Running(position, of, progress))
}

/** What was fetched: what the verifier needs to know about it. */
/** [runtimeMs]: the downloaded source's own runtime, when the server stated one (W-1); else the check uses the row's. */
internal class QueueFetched(val entry: File, val hls: Boolean, val shape: List<Long>?, val audioListed: Boolean, val runtimeMs: Long? = null)

/**
 * A remembered conversion, with the request of the row it was opened for (Tasks 11-12 review I-4) and the account that opened
 * it (Tasks 13-16 review M-3): it is reused only for that same row (same `requestedAt`, same quality, its container already HLS)
 * fetched by that same account, never for a new row of the same item and never under another profile's sign-in.
 */
internal class HeldConversion(
    val conv: LiveConversion,
    private val requestedAt: Long,
    private val quality: DownloadQuality,
    private val fetcher: app.shoalwatch.data.servers.Session,
) {
    fun isFor(row: app.shoalwatch.data.db.downloads.DownloadEntity, s: app.shoalwatch.data.servers.Session) =
        s == fetcher && row.requestedAt == requestedAt && row.quality == quality && row.container == QueueSpace.HLS

    override fun toString() = "HeldConversion(<redacted>)"
}
