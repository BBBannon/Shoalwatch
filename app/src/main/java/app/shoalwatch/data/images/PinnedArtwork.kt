package app.shoalwatch.data.images

import app.shoalwatch.core.AppLog
import app.shoalwatch.data.db.downloads.DownloadDao
import app.shoalwatch.data.downloads.ArtworkSlot
import app.shoalwatch.data.downloads.DownloadPaths
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Pictures kept with a download, outside Coil's evictable cache: `<internal root>/<serverId>/art/<itemId>-<SLOT>-<tag>.img`
 * (INTERNAL storage only, ever; [DownloadPaths.artworkFile]) with a `pinned_artwork` row each. A file at rest is untrusted again
 * when read, so [find] runs the same checks as a download ([ArtworkValidation]) every time. See "Artwork" in docs/ARCHITECTURE.md.
 */
class PinnedArtwork internal constructor(
    private val downloads: DownloadDao,
    private val paths: DownloadPaths,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The saved bytes for this ref's (server, item, type, tag), re-checked with the artwork rules; null if none or if they fail (then deleted). */
    internal suspend fun find(ref: ArtworkRef): ByteArray? = withContext(io) {
        val slot = slotOf(ref.type)
        val serverId = ref.session.serverId
        if (downloads.pinnedArtwork(serverId, ref.itemId)[slot] != ref.tag) return@withContext null
        val file = fileOrNull(serverId, ref.itemId, slot, ref.tag) ?: return@withContext null
        val bytes = try {
            if (!file.isFile || file.length() > ArtworkLimits.MAX_BYTES) null else file.readBytes().also { ArtworkValidation.check(null, it) }
        } catch (e: ArtworkRejectedException) {
            null
        } catch (e: IOException) {
            null
        }
        if (bytes == null) {
            // Missing, damaged or tampered with: neither the file nor its row is trusted again.
            AppLog.d(TAG, "pinned artwork dropped")
            paths.deleteInside(file)
            downloads.unpinArtwork(serverId, ref.itemId, slot)
        }
        bytes
    }

    /** Writes [bytes] (already checked by [ArtworkRequest.download]) and fsyncs them, then records the row; an older tag's file goes. */
    internal suspend fun save(serverId: String, itemId: String, slot: ArtworkSlot, tag: String, bytes: ByteArray) = withContext(io) {
        val file = paths.artworkFile(serverId, itemId, slot, tag)
        val folder = file.parentFile ?: throw IOException()
        if (!folder.isDirectory && !folder.mkdirs() && !folder.isDirectory) throw IOException()
        FileOutputStream(file).use { out -> out.write(bytes); out.fd.sync() }
        val previous = downloads.pinnedArtwork(serverId, itemId)[slot]
        downloads.pinArtwork(serverId, itemId, slot, tag, bytes.size.toLong(), System.currentTimeMillis())
        if (previous != null && previous != tag) fileOrNull(serverId, itemId, slot, previous)?.let { paths.deleteInside(it) }
    }

    /** Whether the picture for this slot is pinned with [tag] and its file is there (the saver skips it then). */
    internal suspend fun has(serverId: String, itemId: String, slot: ArtworkSlot, tag: String): Boolean = withContext(io) {
        downloads.pinnedArtwork(serverId, itemId)[slot] == tag && fileOrNull(serverId, itemId, slot, tag)?.isFile == true
    }

    /** Null when internal storage is not there or the names are refused (fixed messages; nothing is echoed). */
    private fun fileOrNull(serverId: String, itemId: String, slot: ArtworkSlot, tag: String): File? = try {
        paths.artworkFile(serverId, itemId, slot, tag)
    } catch (e: IllegalArgumentException) {
        null
    } catch (e: IllegalStateException) {
        null
    }

    override fun toString() = "PinnedArtwork"

    internal companion object {
        private const val TAG = "Artwork"

        fun slotOf(type: ArtworkType): ArtworkSlot = when (type) {
            ArtworkType.PRIMARY -> ArtworkSlot.PRIMARY
            ArtworkType.BACKDROP -> ArtworkSlot.BACKDROP
            ArtworkType.THUMB -> ArtworkSlot.THUMB
        }
    }
}
