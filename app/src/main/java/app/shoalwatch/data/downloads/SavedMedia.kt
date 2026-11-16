package app.shoalwatch.data.downloads

import java.io.File

/**
 * What the player may open for one download: the folder it may read inside and the entry file (`media.<ext>` for an original,
 * `hls/local.m3u8` for a conversion). Built only from `DownloadPaths` results; `ItemBytes` re-checks the real path of every
 * file it opens against [folder]. Never printed.
 */
class SavedMedia internal constructor(
    val serverId: String,
    val itemId: String,
    internal val folder: File,
    internal val entry: File,
    val hls: Boolean,
    val quality: DownloadQuality,
    internal val mediaSourceId: String,
) {
    override fun toString() = "SavedMedia(<redacted>)"
}
