package app.shoalwatch.data.downloads

import app.shoalwatch.data.db.downloads.DownloadEntity
import app.shoalwatch.data.playback.MediaPaths
import java.io.File

/** The queue's space and file rules for one row (pure apart from [Volumes] and [DownloadPaths]). */
internal object QueueSpace {
    const val HLS = "hls"
    /** The plan's reserves (Decisions): 1 GiB on internal storage, 256 MiB on a card. */
    const val INTERNAL_RESERVE = 1L shl 30
    const val REMOVABLE_RESERVE = 256L shl 20
    private const val UNKNOWN_RUNTIME_MS = 45L * 60 * 1_000

    /** Free space for [bytes] more, above the reserve (saturating: no size can wrap round to fit). */
    fun fits(volumes: Volumes, row: DownloadEntity, bytes: Long): Boolean {
        val reserve = if (row.storage == StorageKind.INTERNAL) INTERNAL_RESERVE else REMOVABLE_RESERVE
        return volumes.freeBytes(row.storage, row.volumeId) >= bytes.coerceIn(0, Long.MAX_VALUE - reserve) + reserve
    }

    /** What is still to come, as far as is known before asking the server: an original's known total, a conversion's estimate. */
    fun remaining(row: DownloadEntity): Long = when (val estimate = estimate(row)) {
        null -> (row.totalBytes ?: 0L) - row.doneBytes
        else -> row.segmentsTotal?.takeIf { it > 0 }?.let { estimate / it * (it - row.segmentsDone) } ?: estimate
    }

    /** A conversion's size at its bitrate over the runtime (unknown or 0: 45 minutes); null for an original. */
    fun estimate(row: DownloadEntity): Long? {
        val bitrate = row.quality.quality?.maxBitrate?.toLong() ?: return null
        return bitrate / 8 * ((row.runtimeMs?.takeIf { it > 0 } ?: UNKNOWN_RUNTIME_MS) / 1_000)
    }

    /** A COMPLETE row must name the file it holds (a safe media source, and a container whose entry is [entry]), or nothing could open it (review I-4). */
    fun names(paths: DownloadPaths, row: DownloadEntity, folder: File, entry: File): Boolean =
        row.mediaSourceId?.let(MediaPaths::isSafeId) == true && entryOf(paths, row, folder)?.canonicalPath == entry.canonicalPath

    /** The saved copy's entry file: `hls/local.m3u8` for a conversion, else `media.<container>`; null before it is known. */
    fun entryOf(paths: DownloadPaths, row: DownloadEntity, folder: File): File? = when (val container = row.container) {
        null -> null
        HLS -> paths.localPlaylist(paths.hlsFolder(folder))
        else -> paths.mediaFile(folder, container)
    }
}
