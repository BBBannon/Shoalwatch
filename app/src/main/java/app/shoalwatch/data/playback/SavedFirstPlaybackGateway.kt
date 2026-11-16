package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.downloads.DownloadPaths
import app.shoalwatch.data.downloads.SavedMedia
import app.shoalwatch.data.downloads.SavedRows
import app.shoalwatch.data.downloads.TrackType
import app.shoalwatch.data.playback.api.PlaybackGateway
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * The gateway handed to the controller and `SubtitleLoader`: saved subtitles and segments for a saved copy this session may
 * play ([saved], the same lookup as the resolver's), else [server]. Everything else is delegated.
 *  - [subtitleFile]: for a saved copy with the same media source and a saved file of that index and format, the file's bytes,
 *    read only through [DownloadPaths] inside the copy's own folder (no link followed), refused over 8 MiB as the server's would
 *    be; the caller then parses them with the same untrusted-input parser (`SubtitleFiles`). No saved file: the server's.
 *  - [segments]: the saved rows of a saved copy (none saved: none, never the server's).
 */
internal class SavedFirstPlaybackGateway(
    private val server: PlaybackGateway,
    private val saved: suspend (Session, String) -> SavedMedia?,
    private val downloads: SavedRows,
    private val paths: DownloadPaths,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PlaybackGateway by server {
    override suspend fun segments(s: Session, itemId: String): List<MediaSegment> {
        val media = saved(s, itemId) ?: return server.segments(s, itemId)
        return downloads.segments(DownloadKey(media.serverId, media.itemId)).map { MediaSegment(it.kind, it.startMs, it.endMs) }
    }

    override suspend fun subtitleFile(s: Session, itemId: String, mediaSourceId: String, index: Int, format: String): ByteArray {
        val media = saved(s, itemId)?.takeIf { it.mediaSourceId == mediaSourceId }
            ?: return server.subtitleFile(s, itemId, mediaSourceId, index, format)
        val track = downloads.tracks(DownloadKey(media.serverId, media.itemId))
            .firstOrNull { it.type == TrackType.SUBTITLE && it.streamIndex == index && it.fileSaved && it.fileFormat == format }
            ?: return server.subtitleFile(s, itemId, mediaSourceId, index, format)
        return withContext(io) {
            val file = try { paths.subtitleFile(media.folder, track.streamIndex, format) } catch (e: IllegalArgumentException) { throw unreadable() }
            read(media, file)
        }
    }

    /** At most [MAX_BYTES], a regular file (not a link) whose real path is inside the copy's folder; else SERVER_ERROR (unreadable). */
    private fun read(media: SavedMedia, file: File): ByteArray = try {
        val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val root = media.folder.toPath().toRealPath()
        if (!attributes.isRegularFile || attributes.size() > MAX_BYTES || !file.toPath().toRealPath().startsWith(root)) throw unreadable()
        FileInputStream(file).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > MAX_BYTES) throw unreadable() // it grew after the size check
                out.write(buffer, 0, n)
            }
            out.toByteArray()
        }
    } catch (e: IOException) {
        throw unreadable()
    }

    private fun unreadable() = AppError.of(AppError.Kind.SERVER_ERROR)

    companion object {
        /** As the server's subtitle files (`SubtitleLoader.MAX_BYTES`). */
        const val MAX_BYTES = SubtitleLoader.MAX_BYTES
        private const val BUFFER = 64 * 1024
    }
}
