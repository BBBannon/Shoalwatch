package app.shoalwatch.data.images

import app.shoalwatch.data.servers.JellyfinId
import coil3.disk.DiskCache
import okio.Path.Companion.toOkioPath
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Saved pictures, one Coil [DiskCache] per server in its own folder (`<directory>/<serverId>`), each capped at [maxBytes].
 * Coil's cache cannot list its keys, so a server's pictures can only be cleared as a unit by giving the server its own cache:
 * [evictServer] is then a `clear` and a folder delete. The folder name is a validated `JellyfinId`, never free text.
 * [create] builds one cache (tests wrap it).
 */
class ArtworkDiskCaches(
    private val directory: File,
    private val maxBytes: Long = DISK_BYTES,
    private val create: (File, Long) -> DiskCache = ::defaultCreate,
) {
    private val caches = ConcurrentHashMap<String, DiskCache>()

    fun forServer(serverId: String): DiskCache {
        require(JellyfinId.parseOrNull(serverId)?.value == serverId) { "Not a server id." }
        return caches.computeIfAbsent(serverId) { create(File(directory, it), maxBytes) }
    }

    /** Deletes every saved picture of [serverId], including folders left by an earlier run. Blocking: call off the main thread. */
    fun evictServer(serverId: String) {
        require(JellyfinId.parseOrNull(serverId)?.value == serverId) { "Not a server id." }
        caches.remove(serverId)?.let {
            it.clear()
            it.shutdown()
        }
        File(directory, serverId).deleteRecursively()
    }

    companion object {
        const val DISK_BYTES = 250L * 1024 * 1024

        fun defaultCreate(dir: File, max: Long): DiskCache = DiskCache.Builder().directory(dir.toOkioPath()).maxSizeBytes(max).build()
    }
}
