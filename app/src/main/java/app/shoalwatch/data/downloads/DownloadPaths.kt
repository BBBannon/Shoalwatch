package app.shoalwatch.data.downloads

import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.servers.JellyfinId
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/** A pinned picture, as the orphan sweep needs to know it (one `pinned_artwork` row). */
data class ArtworkKey(val serverId: String, val itemId: String, val slot: ArtworkSlot, val tag: String) {
    override fun toString() = "ArtworkKey(<redacted>)"
}

/** What one sweep did: how many entries it deleted, and whether it stopped at the entry limit before looking at everything. */
data class SweepResult(val deleted: Int, val stoppedAtLimit: Boolean)

/**
 * THE ONLY CODE THAT TURNS IDS INTO FILE NAMES. Layout, under each root ([Volumes.root]):
 * `<serverId>/<itemId>/media.<ext>` (+ `.part` while fetching), `sub-<index>.<format>`, `hls/seg-00000.ts` (+ `.part` while
 * fetching), `hls/local.m3u8`;
 * and on INTERNAL only `<serverId>/art/<itemId>-<SLOT>-<tag>.img`. Names come only from `JellyfinId`-normalised ids, a
 * validated image tag, a validated index and fixed words; no server string ever becomes part of a path.
 *
 * Every builder `require`s valid input (fixed messages that never echo it) and then that the result's canonical path lies
 * inside its root. Deleting checks again and never follows a link (a symbolic link, or a Windows junction in tests, is
 * removed as itself; its target is untouched).
 */
internal class DownloadPaths(private val volumes: Volumes, private val sweepLimit: Int = SWEEP_LIMIT) {
    fun itemFolder(key: DownloadKey, storage: StorageKind, volumeId: String?): File? {
        requireId(key.serverId)
        requireId(key.itemId)
        val root = volumes.root(storage, volumeId) ?: return null
        return inside(root, File(File(root, key.serverId), key.itemId))
    }

    fun mediaFile(folder: File, container: String): File = itemChild(folder, "media.${extension(container)}")

    fun partFile(folder: File, container: String): File = itemChild(folder, "media.${extension(container)}$PART")

    fun hlsFolder(folder: File): File = itemChild(folder, HLS)

    fun segmentFile(hls: File, index: Int): File {
        require(index in 0 until MAX_SEGMENTS) { "Segment index out of range." }
        return hlsChild(hls, "seg-%05d.ts".format(index))
    }

    /** `seg-<n>.ts.part`: a segment while it is being written, before its fsync and rename to [segmentFile]. */
    fun segmentPartFile(hls: File, index: Int): File {
        require(index in 0 until MAX_SEGMENTS) { "Segment index out of range." }
        return hlsChild(hls, "seg-%05d.ts$PART".format(index))
    }

    fun localPlaylist(hls: File): File = hlsChild(hls, LOCAL_PLAYLIST)

    fun subtitleFile(folder: File, streamIndex: Int, fileFormat: String): File {
        require(streamIndex in 0..MAX_STREAM_INDEX) { "Stream index out of range." }
        require(fileFormat in SUBTITLE_FORMATS) { "Not an allowed subtitle format." }
        return itemChild(folder, "sub-$streamIndex.$fileFormat")
    }

    /** `sub-<index>.<format>.part`: a subtitle while it is being written, before its fsync and rename to [subtitleFile]. */
    fun subtitlePartFile(folder: File, streamIndex: Int, fileFormat: String): File = subtitleFile(folder, streamIndex, fileFormat).let { itemChild(folder, it.name + PART) }

    fun artworkFile(serverId: String, itemId: String, slot: ArtworkSlot, tag: String): File {
        requireId(serverId)
        requireId(itemId)
        require(TAG.matches(tag)) { "Not a valid image tag." }
        val root = checkNotNull(volumes.root(StorageKind.INTERNAL, null)) { "Internal storage is not available." }
        return inside(root, File(File(File(root, serverId), ART), "$itemId-${slot.name}-$tag$IMAGE"))
    }

    /** `<root>/<serverId>` on every mounted root (they may not exist). */
    fun serverFolders(serverId: String): List<File> {
        requireId(serverId)
        return volumes.allRoots().map { inside(it, File(it, serverId)) }
    }

    /** `<root>/<serverId>/<itemId>` on every mounted root (an item may be on either; they may not exist). */
    fun itemFolders(key: DownloadKey): List<File> {
        requireId(key.itemId)
        return serverFolders(key.serverId).map { inside(it, File(it, key.itemId)) }
    }

    /** Deletes [target] and everything under it without following links, only if its own path is inside a root (never the root itself). */
    fun deleteInside(target: File): Boolean {
        val path = located(target) ?: return false
        val root = volumes.allRoots().map(::canonical).firstOrNull { path.startsWith(it + File.separator) } ?: return false
        return deleteTree(File(path).toPath(), root)
    }

    /**
     * Removes what no saved server, download or pinned picture accounts for, under each mounted root: a `<serverId>` folder
     * whose server is not in [servers]; an `<itemId>` folder not in [known]; an `art/` file not in [artwork]; and anything whose
     * name does not match the layout above (a link included, removed as itself). Looks at no more than 100,000 entries per run,
     * never leaves a root, and a root that disappears mid-sweep (a card taken out) is skipped, never thrown.
     */
    fun sweep(known: Set<DownloadKey>, servers: Set<String>, artwork: Set<ArtworkKey>): SweepResult {
        val run = Sweep(known, servers, artwork)
        val internal = runCatching { volumes.root(StorageKind.INTERNAL, null)?.let(::canonical) }.getOrNull()
        for (root in volumes.allRoots()) {
            if (run.full) break
            try {
                val base = canonical(root)
                run.root(base, isInternal = base == internal)
            } catch (e: IOException) {
                continue // the root went away (a card taken out): the next sweep tries again
            } catch (e: SecurityException) {
                continue
            }
        }
        return SweepResult(run.deleted, run.full)
    }

    private inner class Sweep(val known: Set<DownloadKey>, val servers: Set<String>, val artwork: Set<ArtworkKey>) {
        var deleted = 0
        var seen = 0
        val full get() = seen >= sweepLimit

        private fun entries(dir: String): List<Path> = Files.newDirectoryStream(File(dir).toPath()).use { stream ->
            buildList { for (p in stream) { if (seen >= sweepLimit) break; seen++; add(p) } }
        }

        private fun remove(entry: Path, root: String) { if (deleteTree(entry, root)) deleted++ }

        private fun isLink(p: Path): Boolean = attributes(p)?.let { it.isSymbolicLink || it.isOther } ?: true

        private fun isDir(p: Path) = attributes(p)?.isDirectory == true && !isLink(p)

        private fun isFile(p: Path) = attributes(p)?.isRegularFile == true

        fun root(root: String, isInternal: Boolean) {
            for (entry in entries(root)) {
                val name = entry.fileName.toString()
                when {
                    name == AndroidVolumes.NOMEDIA && isFile(entry) -> Unit
                    !isId(name) || !isDir(entry) || name !in servers -> remove(entry, root)
                    else -> server(entry, name, root, isInternal)
                }
            }
        }

        private fun server(folder: Path, serverId: String, root: String, isInternal: Boolean) {
            for (entry in entries(folder.toString())) {
                val name = entry.fileName.toString()
                when {
                    name == ART && isInternal && isDir(entry) -> art(entry, serverId, root)
                    isId(name) && isDir(entry) && DownloadKey(serverId, name) in known -> item(entry, root)
                    else -> remove(entry, root)
                }
            }
        }

        private fun art(folder: Path, serverId: String, root: String) {
            for (entry in entries(folder.toString())) {
                val found = ART_FILE.matchEntire(entry.fileName.toString())
                val key = found?.let { m -> ArtworkSlot.entries.firstOrNull { it.name == m.groupValues[2] }?.let { ArtworkKey(serverId, m.groupValues[1], it, m.groupValues[3]) } }
                if (key == null || key !in artwork || !isFile(entry)) remove(entry, root)
            }
        }

        private fun item(folder: Path, root: String) {
            for (entry in entries(folder.toString())) {
                val name = entry.fileName.toString()
                when {
                    name == HLS && isDir(entry) -> for (f in entries(entry.toString())) { if (!isHlsName(f.fileName.toString()) || !isFile(f)) remove(f, root) }
                    isItemFileName(name) && isFile(entry) -> Unit
                    else -> remove(entry, root)
                }
            }
        }
    }

    // ---- checks

    private fun requireId(id: String) = require(JellyfinId.parseOrNull(id)?.value == id) { "Not a normalised id." }

    private fun extension(container: String): String {
        require(container in MEDIA_EXTENSIONS) { "Not an allowed extension." }
        return container
    }

    /** [file] if its real path is strictly inside [root]'s; otherwise a fixed-message IllegalArgumentException. */
    private fun inside(root: File, file: File): File {
        require(canonical(file).startsWith(canonical(root) + File.separator)) { "Path is outside the downloads folder." }
        return file
    }

    /**
     * The real path of [file]: the part that exists with every link resolved (`Path.toRealPath`, which also resolves a
     * Windows junction that `File.canonicalPath` does not), then the names below it. A path with a `.` or `..` part is refused
     * outright (no builder makes one), so nothing lexical can disagree with what the file system would open.
     */
    private fun canonical(file: File): String {
        val absolute = file.absoluteFile
        require(absolute.toPath().none { it.toString() == "." || it.toString() == ".." }) { "Path is outside the downloads folder." }
        var existing: File = absolute
        val below = ArrayDeque<String>()
        while (!Files.exists(existing.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            below.addFirst(existing.name)
            existing = existing.parentFile ?: break
        }
        val base = try { existing.toPath().toRealPath().toString() } catch (e: IOException) { existing.canonicalPath }
        return below.fold(base) { acc, name -> acc + File.separator + name }
    }

    /** The real path of [target]'s parent plus its own name: where the entry itself is, without following it if it is a link. */
    private fun located(target: File): String? = try {
        val parent = target.absoluteFile.parentFile ?: return null
        canonical(parent) + File.separator + target.name
    } catch (e: IOException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /** The root and the path parts below it of [file] (which must be inside a mounted root), else a fixed-message refusal. */
    private fun parts(file: File): Pair<File, List<String>> {
        val path = canonical(file)
        val root = volumes.allRoots().firstOrNull { path.startsWith(canonical(it) + File.separator) }
        require(root != null) { "Path is outside the downloads folder." }
        return root to path.substring(canonical(root).length + 1).split(File.separatorChar)
    }

    /** [name] inside an item folder (`<root>/<serverId>/<itemId>`), checked to be inside the root. */
    private fun itemChild(folder: File, name: String): File {
        val (root, below) = parts(folder)
        require(below.size == 2 && below.all(::isId)) { "Not a download folder." }
        return inside(root, File(folder, name))
    }

    private fun hlsChild(hls: File, name: String): File {
        val (root, below) = parts(hls)
        require(below.size == 3 && isId(below[0]) && isId(below[1]) && below[2] == HLS) { "Not a download folder." }
        return inside(root, File(hls, name))
    }

    /** Deletes [entry] (a link as itself; a folder's contents first) if its own location is inside [root]. */
    private fun deleteTree(entry: Path, root: String): Boolean {
        val where = located(entry.toFile()) ?: return false
        if (!where.startsWith(root + File.separator)) return false
        val attrs = attributes(entry) ?: return false
        return try {
            if (attrs.isDirectory && !attrs.isSymbolicLink && !attrs.isOther) {
                Files.newDirectoryStream(entry).use { children -> children.toList() }.forEach { deleteTree(it, root) }
            }
            Files.deleteIfExists(entry)
        } catch (e: IOException) {
            false
        }
    }

    companion object {
        /** The containers an original may be saved as (the file name's extension). */
        val MEDIA_EXTENSIONS = setOf("mp4", "m4v", "mov", "mkv", "webm", "ts")

        /** The most entries one sweep looks at. */
        const val SWEEP_LIMIT = 100_000

        private const val PART = ".part"
        private const val HLS = "hls"
        private const val ART = "art"
        private const val IMAGE = ".img"
        private const val LOCAL_PLAYLIST = "local.m3u8"
        private const val MAX_SEGMENTS = 20_000
        private const val MAX_STREAM_INDEX = 10_000
        private val SUBTITLE_FORMATS = setOf("srt", "vtt", "ass", "ssa")
        private val TAG = Regex("[A-Za-z0-9]{1,64}")
        private val ID = Regex("[0-9a-f]{32}")
        private val ART_FILE = Regex("([0-9a-f]{32})-([A-Z]+)-([A-Za-z0-9]{1,64})\\.img")
        private val SEGMENT = Regex("seg-(\\d{5})\\.ts(\\.part)?")
        private val SUBTITLE = Regex("sub-(\\d{1,5})\\.(srt|vtt|ass|ssa)")

        private fun isId(name: String) = ID.matches(name)

        /** A volume's downloads root (`<dir>/downloads`), for [Volumes]: the one other place a download path starts. */
        fun rootIn(dir: File): File = File(dir, AndroidVolumes.ROOT)

        /** The `.nomedia` marker in a downloads root, so no gallery or media scanner lists the films. */
        fun noMediaIn(root: File): File = File(root, AndroidVolumes.NOMEDIA)

        /** Segment [index] of a saved conversion's `hls/` folder, by the name the fetcher saved it under (for the verifier). */
        fun savedSegment(hls: File, index: Int): File {
            require(index in 0 until MAX_SEGMENTS) { "Segment index out of range." }
            return File(hls, "seg-%05d.ts".format(index))
        }

        private fun isHlsName(name: String) = name == LOCAL_PLAYLIST || SEGMENT.matchEntire(name)?.groupValues?.get(1)?.toInt()?.let { it < MAX_SEGMENTS } == true

        private fun isItemFileName(name: String): Boolean {
            val media = name.removeSuffix(PART)
            if (media.startsWith("media.") && media.removePrefix("media.") in MEDIA_EXTENSIONS) return true
            val sub = name.removeSuffix(PART) // a subtitle being written keeps its `.part` until the rename
            return SUBTITLE.matchEntire(sub)?.groupValues?.get(1)?.toInt()?.let { it <= MAX_STREAM_INDEX && sub == "sub-$it.${sub.substringAfterLast('.')}" } == true
        }

        private fun attributes(p: Path): BasicFileAttributes? =
            try { Files.readAttributes(p, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) } catch (e: IOException) { null }
    }
}
