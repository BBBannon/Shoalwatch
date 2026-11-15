package app.shoalwatch.data.downloads

import app.shoalwatch.data.db.downloads.DownloadKey
import java.io.File

/* How one item's run in [DownloadQueue] flows: its outcome, the ends that cut it short, and what it carries. */

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
internal class QueueFetched(val entry: File, val hls: Boolean, val shape: List<Long>?, val audioListed: Boolean)

/**
 * A remembered conversion, with the request of the row it was opened for (Tasks 11-12 review I-4): it is reused only for that
 * same row (same `requestedAt`, same quality, its container already HLS), never for a new row of the same item.
 */
internal class HeldConversion(val conv: LiveConversion, private val requestedAt: Long, private val quality: DownloadQuality) {
    fun isFor(row: app.shoalwatch.data.db.downloads.DownloadEntity) = row.requestedAt == requestedAt && row.quality == quality && row.container == QueueSpace.HLS
}
