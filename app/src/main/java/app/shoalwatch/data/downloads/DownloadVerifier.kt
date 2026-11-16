package app.shoalwatch.data.downloads

import app.shoalwatch.data.playback.CodecSupport
import app.shoalwatch.data.playback.DirectPlayVideo
import app.shoalwatch.data.playback.MediaPaths
import app.shoalwatch.data.playback.engine.PlayabilityProbe
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlin.math.abs

internal sealed interface Verdict {
    data class Good(val sha256: String) : Verdict
    /** [reason] is a fixed word, for the log only. */
    data class Bad(val reason: String) : Verdict
}

/**
 * "Checked playable": every check must pass, in this order (each [Verdict.Bad] reason is a fixed word):
 * 1. **complete** — an original's length equals the expected bytes; a conversion's `local.m3u8` names `seg-00000.ts` onwards in
 *    order, each present and non-empty, no other file is in `hls/`, and the count matches the planned shape when it is known;
 * 2. **signature** — the first bytes match the container (`ftyp` at 4 for mp4/m4v/mov; `1A 45 DF A3` for mkv/webm; `0x47` at
 *    0, 188 and 376 for ts, on every segment of a conversion);
 * 3. **readable** — the probe finds a video track, and an audio track when the item listed audio;
 * 4. **decodable** — every video type is one the device profile offers for direct play ([DirectPlayVideo]: h264 always, the
 *    counted codecs, Dolby Vision with HEVC), and an audio type is decodable;
 * 5. **length** — the duration is within 5% of the runtime (10% for a conversion) when both are known;
 * 6. **fingerprint** — a SHA-256 of the media (the segments in order for a conversion), computed while re-reading it.
 *
 * Files are read without following links; a link anywhere is "complete" failing. Nothing is logged here.
 */
internal class DownloadVerifier(private val probe: PlayabilityProbe, private val codecs: CodecSupport, private val io: CoroutineDispatcher) : PlayableCheck {

    /** [audioListed]: the item's own tracks named audio (a film with no sound may have none). */
    override suspend fun verify(media: SavedMedia, expectedBytes: Long?, runtimeMs: Long?, hlsShape: List<Long>?, audioListed: Boolean): Verdict {
        if (!MediaPaths.isSafeId(media.mediaSourceId)) return Verdict.Bad(RECORD) // never COMPLETE without its media source (review I-4)
        val files = withContext(io) { mediaFiles(media, expectedBytes, hlsShape) } ?: return Verdict.Bad(INCOMPLETE)
        if (!withContext(io) { signed(media, files) }) return Verdict.Bad(SIGNATURE)
        val sha = withContext(io) { sha256(files) } ?: return Verdict.Bad(INCOMPLETE)
        val found = probe.probe(media) ?: return Verdict.Bad(UNREADABLE)
        if (found.videoMimes.isEmpty()) return Verdict.Bad(NO_VIDEO)
        if (audioListed && found.audioMimes.isEmpty()) return Verdict.Bad(NO_AUDIO)
        val device = withContext(io) { codecs.read() }
        val playable = DirectPlayVideo.media3Types(device) // the same list the planner's device profile offers
        if (!found.videoMimes.all { it in playable }) {
            return Verdict.Bad(VIDEO_CODEC)
        }
        if (found.audioMimes.isNotEmpty() && found.audioMimes.none { AUDIO[it] in device.audio }) return Verdict.Bad(AUDIO_CODEC)
        val duration = found.durationMs
        if (duration != null && runtimeMs != null && runtimeMs > 0) {
            val allowed = runtimeMs / if (media.hls) 10 else 20
            if (abs(duration - runtimeMs) > allowed) return Verdict.Bad(LENGTH)
        }
        return Verdict.Good(sha)
    }

    /** Re-reads the media and compares it with the stored hash (after a playback failure, or "Check again"). */
    override suspend fun recheck(media: SavedMedia, sha256: String): Boolean = withContext(io) {
        val files = mediaFiles(media, expectedBytes = null, hlsShape = null, lengthKnown = false) ?: return@withContext false
        sha256(files)?.let { MessageDigest.isEqual(it.toByteArray(), sha256.lowercase().toByteArray()) } == true
    }

    /** The media files in order, or null if anything is missing, empty, a link, the wrong length or not in the playlist's shape. */
    private fun mediaFiles(media: SavedMedia, expectedBytes: Long?, hlsShape: List<Long>?, lengthKnown: Boolean = true): List<File>? {
        if (!insideFolder(media)) return null
        if (!media.hls) {
            val size = regularSize(media.entry) ?: return null
            if (lengthKnown && (expectedBytes == null || size != expectedBytes)) return null
            return if (size > 0) listOf(media.entry) else null
        }
        val hls = media.entry.parentFile ?: return null
        if (regularSize(media.entry) == null || media.entry.length() > MAX_PLAYLIST_BYTES) return null
        val named = try {
            media.entry.readLines(Charsets.UTF_8).map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
        } catch (e: IOException) {
            return null
        }
        if (named.isEmpty() || named.indices.any { named[it] != SEGMENT_NAME.format(it) }) return null
        if (hlsShape != null && hlsShape.size != named.size) return null
        val present = hls.list()?.toSet() ?: return null
        if (present != named.toSet() + media.entry.name) return null
        val files = named.indices.map { DownloadPaths.savedSegment(hls, it) } // the names equal these, checked above
        return if (files.all { (regularSize(it) ?: 0) > 0 }) files else null
    }

    /** The entry's real path (links resolved) lies inside the folder's, as `ItemBytes` will require when it is played. */
    private fun insideFolder(media: SavedMedia): Boolean = try {
        val root = media.folder.toPath().toRealPath()
        val entry = media.entry.toPath().toRealPath()
        entry != root && entry.startsWith(root) &&
            media.entry.parentFile?.toPath()?.let { Files.readAttributes(it, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS) }
                ?.let { it.isDirectory && !it.isSymbolicLink && !it.isOther } == true
    } catch (e: IOException) {
        false
    }

    private fun signed(media: SavedMedia, files: List<File>): Boolean {
        if (media.hls) return files.all(::isTs)
        return when (media.entry.extension.lowercase()) {
            "mp4", "m4v", "mov" -> head(media.entry, 8)?.let { it.size == 8 && it.copyOfRange(4, 8).contentEquals(FTYP) } == true
            "mkv", "webm" -> head(media.entry, 4)?.contentEquals(EBML) == true
            "ts" -> isTs(media.entry)
            else -> false
        }
    }

    private fun isTs(file: File): Boolean {
        val bytes = head(file, TS_PACKET * 2 + 1) ?: return false
        return bytes.size == TS_PACKET * 2 + 1 && (0..2).all { bytes[it * TS_PACKET] == SYNC }
    }

    private fun head(file: File, n: Int): ByteArray? = try {
        file.inputStream().use { input -> input.readNBytesCompat(n) }
    } catch (e: IOException) {
        null
    }

    private fun sha256(files: List<File>): String? = try {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER)
        for (file in files) {
            file.inputStream().use { input ->
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (e: IOException) {
        null
    }

    private companion object {
        const val INCOMPLETE = "incomplete"
        const val SIGNATURE = "signature"
        const val UNREADABLE = "unreadable"
        const val NO_VIDEO = "no-video"
        const val NO_AUDIO = "no-audio"
        const val VIDEO_CODEC = "video-codec"
        const val AUDIO_CODEC = "audio-codec"
        const val LENGTH = "length"
        /** The row does not name the file it holds (no container or media source): never COMPLETE (review I-4). */
        const val RECORD = "record"

        const val SEGMENT_NAME = "seg-%05d.ts"
        const val MAX_PLAYLIST_BYTES = 2L * 1024 * 1024
        const val TS_PACKET = 188
        const val SYNC = 0x47.toByte()
        const val BUFFER = 1 shl 20
        val FTYP = byteArrayOf(0x66, 0x74, 0x79, 0x70)
        val EBML = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())

        /** Media3's audio MIME types as the Jellyfin names `DeviceCapabilities.audio` holds. */
        val AUDIO = mapOf(
            "audio/mp4a-latm" to "aac", "audio/mpeg" to "mp3", "audio/opus" to "opus", "audio/flac" to "flac", "audio/vorbis" to "vorbis",
            "audio/ac3" to "ac3", "audio/eac3" to "eac3", "audio/eac3-joc" to "eac3", "audio/vnd.dts" to "dts", "audio/vnd.dts.hd" to "dts",
            "audio/true-hd" to "truehd",
        )

        /** The size of a regular file that is not a link, or null. */
        fun regularSize(file: File): Long? = try {
            Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
                .takeIf { it.isRegularFile && !it.isSymbolicLink }?.size()
        } catch (e: IOException) {
            null
        }

        fun InputStream.readNBytesCompat(n: Int): ByteArray {
            val out = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = read(out, got, n - got)
                if (r < 0) break
                got += r
            }
            return out.copyOf(got)
        }
    }
}
