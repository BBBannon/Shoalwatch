package app.shoalwatch.data.downloads

import java.io.File

/**
 * [Volumes] over test folders: an internal root and any number of cards, each of which can be taken out ([unmount]) or put
 * back. Roots are made, with `.nomedia`, the first time they are asked for, like [AndroidVolumes].
 */
class FakeVolumes(base: File) {
    val internalBase = File(base, "internal")
    private val cards = linkedMapOf<String, File>()
    private val mounted = mutableSetOf<String>()
    var free = mutableMapOf<String?, Long>()
    val reserved = mutableListOf<Pair<File, Long>>()
    /** Thrown by [Volumes.allRoots] (every delete goes through it) while set. */
    var allRootsFail: Throwable? = null

    fun card(uuid: String, dir: File): FakeVolumes { cards[uuid] = dir; mounted += uuid; return this }
    fun unmount(uuid: String) { mounted -= uuid }
    fun mount(uuid: String) { mounted += uuid }

    private fun made(dir: File) = dir.also { it.mkdirs(); File(it, AndroidVolumes.NOMEDIA).createNewFile() }

    val volumes = object : Volumes {
        override fun root(kind: StorageKind, volumeId: String?): File? = when (kind) {
            StorageKind.INTERNAL -> made(File(internalBase, AndroidVolumes.ROOT))
            StorageKind.REMOVABLE -> volumeId?.takeIf { it in mounted }?.let { cards[it] }?.let { made(File(it, AndroidVolumes.ROOT)) }
        }

        override fun choices() = listOf(StorageChoice(StorageKind.INTERNAL, null, free[null] ?: 0, true)) +
            mounted.map { StorageChoice(StorageKind.REMOVABLE, it, free[it] ?: 0, true) }

        override fun freeBytes(kind: StorageKind, volumeId: String?) = free[if (kind == StorageKind.INTERNAL) null else volumeId] ?: 0

        override fun reserve(file: File, bytes: Long) { reserved += file to bytes }

        override fun allRoots(): List<File> = allRootsFail?.let { throw it } ?: listOfNotNull(root(StorageKind.INTERNAL, null)) + mounted.mapNotNull { root(StorageKind.REMOVABLE, it) }
    }
}
