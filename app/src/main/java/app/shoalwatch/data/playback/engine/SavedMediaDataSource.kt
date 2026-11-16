package app.shoalwatch.data.playback.engine

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Reads one saved file for [ItemBytes], which has already checked that its real path lies inside the current item's folder.
 * Not a network source. Honours the [DataSpec]'s position and length; refuses anything that is not a regular file, or that is
 * a symbolic link. Errors carry fixed messages only (never the path).
 */
@OptIn(UnstableApi::class)
internal class SavedMediaDataSource : BaseDataSource(false) {
    private var file: RandomAccessFile? = null
    private var uri: Uri? = null
    private var remaining = 0L
    private var opened = false

    /** [target] is the file [ItemBytes] resolved from the spec's URI. */
    fun open(spec: DataSpec, target: File): Long {
        uri = spec.uri
        transferInitializing(spec)
        val path = target.toPath()
        val attributes = try {
            Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        }
        if (!attributes.isRegularFile || attributes.isSymbolicLink || Files.isSymbolicLink(path)) {
            throw DataSourceException(IOException("Not a saved file."), PlaybackException.ERROR_CODE_IO_NO_PERMISSION)
        }
        val raf = try {
            RandomAccessFile(target, "r")
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND)
        }
        file = raf
        val length = raf.length()
        if (spec.position > length) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        remaining = if (spec.length == C.LENGTH_UNSET.toLong()) length - spec.position else spec.length
        if (remaining < 0 || spec.position + remaining > length) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        raf.seek(spec.position)
        opened = true
        transferStarted(spec)
        return remaining
    }

    /** Only [ItemBytes] opens it, with the file it checked. */
    override fun open(dataSpec: DataSpec): Long = throw IOException("Opened only through ItemBytes.")

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val n = try {
            checkNotNull(file) { "Not open." }.read(buffer, offset, minOf(remaining, length.toLong()).toInt())
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        }
        if (n < 0) return C.RESULT_END_OF_INPUT
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try {
            file?.close()
        } catch (e: IOException) {
            throw DataSourceException(e, PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        } finally {
            file = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }
}
