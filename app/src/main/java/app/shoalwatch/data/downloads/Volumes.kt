package app.shoalwatch.data.downloads

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import androidx.core.content.ContextCompat
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** Where a download may be saved, how much may be written there, and whether it is in the phone now. */
data class StorageChoice(val kind: StorageKind, val volumeId: String?, val freeBytes: Long, val available: Boolean)

interface Volumes {
    /** The downloads root for [kind] (and [volumeId] for REMOVABLE), creating it with a `.nomedia` file; null when not mounted. */
    fun root(kind: StorageKind, volumeId: String?): File?

    /** INTERNAL always; then each mounted removable volume. */
    fun choices(): List<StorageChoice>

    /** Bytes that may be written: StorageManager.getAllocatableBytes for INTERNAL, File.usableSpace for REMOVABLE. */
    fun freeBytes(kind: StorageKind, volumeId: String?): Long

    /** Reserve the space before an original download starts (StorageManager.allocateBytes on INTERNAL; no-op on REMOVABLE). */
    fun reserve(file: File, bytes: Long)

    /** Every mounted root, for the orphan sweep. */
    fun allRoots(): List<File>
}

/**
 * App-private storage only, so no permission is needed and everything goes on uninstall. INTERNAL is `filesDir/downloads`
 * (sandboxed, file-based encryption). REMOVABLE is `<app-specific dir>/downloads` on each entry of
 * `ContextCompat.getExternalFilesDirs` after index 0 whose volume is removable, mounted and has a uuid matching
 * `[A-Za-z0-9-]{1,36}` (a volume without one is not offered: the uuid is how a download finds its card again).
 *
 * [externalDirs], [mediaState] and [allocatable] are seams for tests (Robolectric cannot list app dirs on a card or make
 * `getAllocatableBytes` fail); the defaults are the platform calls named above.
 */
class AndroidVolumes internal constructor(
    context: Context,
    private val externalDirs: () -> List<File?>,
    private val mediaState: (File) -> String,
    private val allocatable: (StorageManager, File) -> Long,
) : Volumes {
    constructor(context: Context) : this(
        context,
        { ContextCompat.getExternalFilesDirs(context, null).toList() },
        { Environment.getExternalStorageState(it) },
        { storage, dir -> storage.getAllocatableBytes(storage.getUuidForPath(dir)) },
    )

    private val app = context.applicationContext
    private val storage: StorageManager? = app.getSystemService(StorageManager::class.java)

    private class Removable(val uuid: String, val dir: File)

    private fun removable(): List<Removable> {
        val manager = storage ?: return emptyList()
        return externalDirs().drop(1).filterNotNull().mapNotNull { dir ->
            val volume = runCatching { manager.getStorageVolume(dir) }.getOrNull() ?: return@mapNotNull null
            val uuid = volume.uuid
            when {
                !volume.isRemovable -> null
                uuid == null || !VOLUME_ID.matches(uuid) -> null
                runCatching { mediaState(dir) }.getOrNull() != Environment.MEDIA_MOUNTED -> null
                else -> Removable(uuid, dir)
            }
        }.distinctBy { it.uuid }
    }

    /** Makes [dir] with a `.nomedia` file in it (so no gallery or media scanner lists the films); null if that fails. */
    private fun ensured(dir: File): File? = try {
        if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) null
        else dir.also { File(it, NOMEDIA).let { marker -> if (!marker.exists()) marker.createNewFile() } }
    } catch (e: IOException) {
        null
    } catch (e: SecurityException) {
        null
    }

    private fun internalRoot(): File? = ensured(File(app.filesDir, ROOT))

    override fun root(kind: StorageKind, volumeId: String?): File? = when (kind) {
        StorageKind.INTERNAL -> internalRoot()
        StorageKind.REMOVABLE -> removable().firstOrNull { it.uuid == volumeId }?.let { ensured(File(it.dir, ROOT)) }
    }

    override fun choices(): List<StorageChoice> =
        listOf(StorageChoice(StorageKind.INTERNAL, null, freeBytes(StorageKind.INTERNAL, null), available = true)) +
            removable().map { StorageChoice(StorageKind.REMOVABLE, it.uuid, it.dir.usableSpace, available = true) }

    override fun freeBytes(kind: StorageKind, volumeId: String?): Long = when (kind) {
        StorageKind.INTERNAL -> {
            val dir = app.filesDir
            val manager = storage
            if (manager == null) dir.usableSpace
            else try {
                allocatable(manager, dir)
            } catch (e: IOException) {
                dir.usableSpace
            } catch (e: RuntimeException) {
                dir.usableSpace
            }
        }
        StorageKind.REMOVABLE -> removable().firstOrNull { it.uuid == volumeId }?.dir?.usableSpace ?: 0L
    }

    /** On INTERNAL only: asks the platform to make [bytes] really available for [file] (it may clear caches); throws IOException if it cannot. */
    override fun reserve(file: File, bytes: Long) {
        val manager = storage ?: return
        val internal = internalRoot()?.canonicalPath ?: return
        if (!file.canonicalPath.startsWith(internal + File.separator)) return
        RandomAccessFile(file, "rw").use { manager.allocateBytes(it.fd, bytes) }
    }

    override fun allRoots(): List<File> = listOfNotNull(internalRoot()) + removable().mapNotNull { ensured(File(it.dir, ROOT)) }

    companion object {
        const val ROOT = "downloads"
        const val NOMEDIA = ".nomedia"
        val VOLUME_ID = Regex("[A-Za-z0-9-]{1,36}")
    }
}
