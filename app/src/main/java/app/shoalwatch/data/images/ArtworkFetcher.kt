package app.shoalwatch.data.images

import app.shoalwatch.core.AppError
import app.shoalwatch.data.api.ServerApi
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.IOException
import okio.Path
import okio.buffer
import java.util.concurrent.atomic.AtomicReference

/** The memory-cache key of an [ArtworkRef]: the same text the disk cache uses (see [ArtworkLimits.cacheKey]). */
internal class ArtworkKeyer : Keyer<ArtworkRef> {
    override fun key(data: ArtworkRef, options: Options): String = ArtworkLimits.cacheKey(data, ArtworkLimits.bucketFor(options.size))
}

/**
 * Loads one [ArtworkRef]: disk cache, then `ServerApi.getBytes` (the authenticated, verified-address path; there is no other
 * way this class can reach a server). Only a response that passes every check is written to the cache (see "Artwork" in
 * docs/ARCHITECTURE.md): an allowed Content-Type, an allowed format by its first bytes, complete, and declared dimensions
 * within the limits. Failures are exceptions that carry nothing from the response; Coil turns them into an error result.
 * No retry here: `ServerApi` already makes its one retry after a lost connection.
 */
internal class ArtworkFetcher(
    private val ref: ArtworkRef,
    private val options: Options,
    private val api: ServerApi,
    private val caches: ArtworkDiskCaches,
    private val io: CoroutineDispatcher,
) : Fetcher {

    private val cache: DiskCache = caches.forServer(ref.session.serverId)

    override suspend fun fetch(): FetchResult {
        val bucket = ArtworkLimits.bucketFor(options.size)
        val key = ArtworkLimits.cacheKey(ref, bucket)
        if (options.diskCachePolicy.readEnabled) fromDisk(key)?.let { return it }
        if (!options.networkCachePolicy.readEnabled) throw AppError.of(AppError.Kind.OFFLINE)
        val downloaded = download(bucket)
        return save(key, downloaded)
    }

    private class Downloaded(val kind: ArtworkLimits.Kind, val bytes: ByteArray)

    private suspend fun download(bucket: Int): Downloaded {
        val query = listOf("tag" to ref.tag, "maxWidth" to bucket.toString(), "quality" to ArtworkLimits.QUALITY.toString(), "format" to FORMAT)
        return api.getBytes(ref.session, "/Items/${ref.itemId}/Images/${ref.type.path}", query, ArtworkLimits.MAX_BYTES) { source, contentType ->
            val declared = ArtworkLimits.kindOfContentType(contentType) ?: throw ArtworkValidation.refuse(ArtworkRejectedException.Reason.CONTENT_TYPE)
            // Early out before the body is read: the first bytes must already agree with the header.
            val first = ArtworkBounds.kind(source) ?: throw ArtworkValidation.refuse(ArtworkRejectedException.Reason.NOT_AN_IMAGE)
            if (first != declared) throw ArtworkValidation.refuse(ArtworkRejectedException.Reason.CONTENT_TYPE)
            val bytes = source.readByteArray()
            val kind = ArtworkValidation.check(declared, bytes)
            Downloaded(kind, bytes)
        }
    }

    /** Saved entries are written only after [ArtworkValidation.check]; a saved one is checked the same way (read once), and a bad one is dropped. */
    private suspend fun fromDisk(key: String): SourceFetchResult? = handingOver { held ->
        val snapshot = cache.openSnapshot(key) ?: return@handingOver null
        held.set(snapshot)
        val kind = try {
            ArtworkValidation.check(null, readSaved(snapshot.data))
        } catch (e: ArtworkRejectedException) {
            null
        } catch (e: IOException) {
            null
        }
        if (kind == null) {
            held.set(null)
            snapshot.close()
            cache.remove(key)
            return@handingOver null
        }
        SourceFetchResult(ImageSource(snapshot.data, cache.fileSystem, key, snapshot, ArtworkSourceInfo { cache.remove(key) }), kind.mime, DataSource.DISK)
    }

    private fun readSaved(file: Path): ByteArray {
        val size = cache.fileSystem.metadata(file).size
        if (size == null || size > ArtworkLimits.MAX_BYTES) throw ArtworkRejectedException(ArtworkRejectedException.Reason.INCOMPLETE)
        return cache.fileSystem.source(file).buffer().use { it.readByteArray() }
    }

    /** Writes the validated bytes to the cache (when allowed) and serves them from there, or straight from memory if the cache will not take them. */
    private suspend fun save(key: String, downloaded: Downloaded): SourceFetchResult = handingOver { held ->
        val snapshot = if (options.diskCachePolicy.writeEnabled) write(key, downloaded.bytes) else null
        held.set(snapshot)
        val source = if (snapshot != null) ImageSource(snapshot.data, cache.fileSystem, key, snapshot, ArtworkSourceInfo { cache.remove(key) })
        else ImageSource(Buffer().write(downloaded.bytes), options.fileSystem, ArtworkSourceInfo { })
        SourceFetchResult(source, downloaded.kind.mime, DataSource.NETWORK)
    }

    /**
     * Runs blocking cache work on [io]. Whatever [block] opens must be put in `held`; if the caller is cancelled while the work
     * runs, `withContext` throws the finished result away, so anything still held is closed here: no snapshot is ever leaked.
     */
    private suspend fun <T> handingOver(block: (AtomicReference<AutoCloseable?>) -> T): T {
        val held = AtomicReference<AutoCloseable?>()
        return try {
            withContext(io) { block(held) }.also { held.set(null) }
        } catch (e: Throwable) {
            held.getAndSet(null)?.close()
            throw e
        }
    }

    private fun write(key: String, bytes: ByteArray): DiskCache.Snapshot? {
        val editor = cache.openEditor(key) ?: return null
        return try {
            cache.fileSystem.write(editor.data) { write(bytes) }
            editor.commitAndOpenSnapshot()
        } catch (e: Exception) {
            // Every Exception (a full disk, a cache shut down by an eviction) falls back to memory; cancellation is not an Exception here, it cannot interrupt this.
            editor.abort()
            null
        }
    }

    class Factory(private val api: ServerApi, private val caches: ArtworkDiskCaches, private val io: CoroutineDispatcher) : Fetcher.Factory<ArtworkRef> {
        override fun create(data: ArtworkRef, options: Options, imageLoader: ImageLoader): Fetcher =
            ArtworkFetcher(data, options, api, caches, io)
    }

    private companion object {
        const val TAG = "Artwork"

        /** `ImageFormat.Webp` in the Jellyfin 12.4.0 reference: decoded by Android 10 and later with no extra library. */
        const val FORMAT = "Webp"
    }
}
