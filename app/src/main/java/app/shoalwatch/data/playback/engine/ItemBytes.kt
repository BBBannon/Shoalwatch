package app.shoalwatch.data.playback.engine

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import app.shoalwatch.core.AppError
import app.shoalwatch.net.BlockedRequestException
import java.io.File
import java.io.IOException
import java.net.URI

/**
 * The one DataSource the player and the probe use. http(s) URIs are passed to [remote] only while the current [EngineItem] is
 * remote; file URIs are opened with [SavedMediaDataSource] only while the current item is saved AND the file's real path (links
 * resolved) is inside its folder. Anything else fails at [open] with an IOException wrapping `BlockedRequestException(FORBIDDEN)`.
 * [current] is read at every open, on Media3's loading threads.
 */
@OptIn(UnstableApi::class)
internal class ItemBytes private constructor(private val remote: DataSource, private val current: () -> EngineItem?) : DataSource {
    private val saved = SavedMediaDataSource()
    private var active: DataSource? = null

    class Factory(private val remote: DataSource.Factory, private val current: () -> EngineItem?) : DataSource.Factory {
        override fun createDataSource(): DataSource = ItemBytes(remote.createDataSource(), current)
    }

    override fun addTransferListener(transferListener: TransferListener) {
        remote.addTransferListener(transferListener)
        saved.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val item = current() ?: throw refused()
        return when (dataSpec.uri.scheme?.lowercase()) {
            "http", "https" -> {
                if (item.route == null) throw refused()
                active = remote
                remote.open(dataSpec)
            }
            "file" -> {
                val media = item.saved ?: throw refused()
                val target = inside(media.folder, dataSpec.uri) ?: throw refused()
                active = saved
                saved.open(dataSpec, target)
            }
            else -> throw refused()
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = checkNotNull(active) { "Not open." }.read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        val source = active ?: return
        active = null
        source.close()
    }

    private companion object {
        fun refused() = IOException("Not this item's bytes.", BlockedRequestException(AppError.of(AppError.Kind.FORBIDDEN)))

        /** The file, if its real path lies strictly inside the real path of [folder]; null for anything else (or unreadable). */
        fun inside(folder: File, uri: Uri): File? = try {
            val file = File(URI(uri.toString()))
            val root = folder.toPath().toRealPath()
            val real = file.toPath().toRealPath()
            if (real != root && real.startsWith(root)) file else null
        } catch (e: Exception) { // a malformed URI, a missing file, an unreadable path
            null
        }
    }
}
