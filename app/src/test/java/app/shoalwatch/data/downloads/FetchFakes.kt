package app.shoalwatch.data.downloads

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.ReadableByteChannel
import java.nio.channels.WritableByteChannel
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * `/Items/{id}/Download` as ASP.NET Core serves a file, with one switch per real-server behaviour the stand-in server has
 * (`--no-range`, `--etag-changes`, `--no-etag`, `--drop-after`, `--octet-stream`). Range is `bytes=N-` only.
 */
class RangeServer(val body: ByteArray = Random(7).nextBytes(128 * 1024)) : Dispatcher() {
    @Volatile var noRange = false
    @Volatile var etagChanges = false
    @Volatile var noEtag = false
    /** Drop the connection half-way through the next body (DISCONNECT_DURING_RESPONSE_BODY); [dropAlways] every body. */
    @Volatile var dropNext = false
    @Volatile var dropAlways = false
    @Volatile var contentType: String? = "video/mp4"
    val requests = CopyOnWriteArrayList<RecordedRequest>()
    private var served = 0

    val etag get() = "\"a1b2c3\""
    val lastModified = "Sun, 15 Nov 2026 10:00:00 GMT"

    @Synchronized override fun dispatch(request: RecordedRequest): MockResponse {
        requests += request
        served++
        val etag = if (etagChanges) "\"v$served\"" else etag
        val modified = if (etagChanges) "Sun, 15 Nov 2026 10:0$served:00 GMT" else lastModified
        val start = request.getHeader("Range")?.let { Regex("bytes=(\\d+)-").matchEntire(it)?.groupValues?.get(1)?.toLong() }
        val ifRange = request.getHeader("If-Range")
        val matches = when {
            ifRange == null -> true
            noEtag -> ifRange.startsWith("\"") || ifRange == modified // an entity tag cannot be compared: ignored, as ASP.NET does
            else -> ifRange == etag || ifRange == modified
        }
        val response = MockResponse().setHeader("Last-Modified", modified)
        if (!noEtag) response.setHeader("ETag", etag)
        contentType?.let { response.setHeader("Content-Type", it) }
        if (!noRange) response.setHeader("Accept-Ranges", "bytes")
        if (!noRange && start != null && matches) {
            if (start >= body.size) return response.setResponseCode(416).setHeader("Content-Range", "bytes */${body.size}")
            response.setResponseCode(206).setHeader("Content-Range", "bytes $start-${body.size - 1}/${body.size}")
                .setBody(Buffer().write(body, start.toInt(), body.size - start.toInt()))
        } else {
            response.setResponseCode(200).setBody(Buffer().write(body))
        }
        if (dropAlways || dropNext) {
            dropNext = false
            response.socketPolicy = SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY
        }
        return response
    }
}

/** A file channel that records the size on disk at each `force` (and can fail a write with [failWrite]). */
class RecordingChannel(private val inner: FileChannel, private val file: File) : FileChannel() {
    val forcedSizes = CopyOnWriteArrayList<Long>()
    @Volatile var failWrite: IOException? = null

    override fun force(metaData: Boolean) { inner.force(metaData); forcedSizes += file.length() }
    override fun write(src: ByteBuffer): Int { failWrite?.let { throw it }; return inner.write(src) }
    override fun write(srcs: Array<out ByteBuffer>, offset: Int, length: Int): Long { failWrite?.let { throw it }; return inner.write(srcs, offset, length) }
    override fun write(src: ByteBuffer, position: Long): Int = inner.write(src, position)
    override fun read(dst: ByteBuffer): Int = inner.read(dst)
    override fun read(dsts: Array<out ByteBuffer>, offset: Int, length: Int): Long = inner.read(dsts, offset, length)
    override fun read(dst: ByteBuffer, position: Long): Int = inner.read(dst, position)
    override fun position(): Long = inner.position()
    override fun position(newPosition: Long): FileChannel { inner.position(newPosition); return this }
    override fun size(): Long = inner.size()
    override fun truncate(size: Long): FileChannel { inner.truncate(size); return this }
    override fun transferTo(position: Long, count: Long, target: WritableByteChannel): Long = inner.transferTo(position, count, target)
    override fun transferFrom(src: ReadableByteChannel, position: Long, count: Long): Long = inner.transferFrom(src, position, count)
    override fun map(mode: MapMode, position: Long, size: Long): MappedByteBuffer = inner.map(mode, position, size)
    override fun lock(position: Long, size: Long, shared: Boolean): FileLock = inner.lock(position, size, shared)
    override fun tryLock(position: Long, size: Long, shared: Boolean): FileLock? = inner.tryLock(position, size, shared)
    override fun implCloseChannel() = inner.close()
}

/** Every progress save, with the part file's length on disk at that moment. */
internal class ProgressLog(private val part: File, private val target: File) : Progress {
    data class Saved(val done: Long, val total: Long?, val validator: String?, val restarts: Int, val onDisk: Long)
    val saves = CopyOnWriteArrayList<Saved>()
    val last get() = saves.last()
    override suspend fun saved(doneBytes: Long, totalBytes: Long?, validator: String?, restarts: Int) {
        saves += Saved(doneBytes, totalBytes, validator, restarts, if (part.exists()) part.length() else target.length())
    }
}
