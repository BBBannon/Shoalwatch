package app.shoalwatch.data.images

import app.shoalwatch.core.AppError
import app.shoalwatch.core.AppLog
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
import java.io.ByteArrayInputStream

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
    private val cache: DiskCache?,
    private val io: CoroutineDispatcher,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val bucket = ArtworkLimits.bucketFor(options.size)
        val key = ArtworkLimits.cacheKey(ref, bucket)
        if (cache != null && options.diskCachePolicy.readEnabled) fromDisk(cache, key)?.let { return it }
        if (!options.networkCachePolicy.readEnabled) throw AppError.of(AppError.Kind.OFFLINE)
        val downloaded = download(bucket)
        return withContext(io) { save(key, downloaded) }
    }

    private class Downloaded(val kind: ArtworkLimits.Kind, val bytes: ByteArray)

    private suspend fun download(bucket: Int): Downloaded {
        val query = listOf("tag" to ref.tag, "maxWidth" to bucket.toString(), "quality" to ArtworkLimits.QUALITY.toString(), "format" to FORMAT)
        return api.getBytes(ref.session, "/Items/${ref.itemId}/Images/${ref.type.path}", query, ArtworkLimits.MAX_BYTES) { source, contentType ->
            if (ArtworkLimits.kindOfContentType(contentType) == null) throw refuse(ArtworkRejectedException.Reason.CONTENT_TYPE)
            val kind = ArtworkBounds.kind(source) ?: throw refuse(ArtworkRejectedException.Reason.NOT_AN_IMAGE)
            val bytes = source.readByteArray()
            if (!ArtworkLimits.isComplete(kind, bytes)) throw refuse(ArtworkRejectedException.Reason.INCOMPLETE)
            val bounds = ArtworkBounds.read(ByteArrayInputStream(bytes))
            if (bounds == null || !ArtworkLimits.dimensionsAcceptable(bounds.first, bounds.second)) throw refuse(ArtworkRejectedException.Reason.DIMENSIONS)
            Downloaded(kind, bytes)
        }
    }

    private fun refuse(reason: ArtworkRejectedException.Reason): ArtworkRejectedException {
        AppLog.d(TAG, "artwork refused: $reason")
        return ArtworkRejectedException(reason)
    }

    /** A saved entry that is still a plausible image of an allowed kind; anything else is dropped and treated as not saved. */
    private suspend fun fromDisk(cache: DiskCache, key: String): SourceFetchResult? = withContext(io) {
        val snapshot = cache.openSnapshot(key) ?: return@withContext null
        try {
            val kind = readable(cache, snapshot.data)
            if (kind == null) {
                snapshot.close()
                cache.remove(key)
                return@withContext null
            }
            SourceFetchResult(ImageSource(snapshot.data, cache.fileSystem, key, snapshot), kind.mime, DataSource.DISK)
        } catch (e: Throwable) {
            snapshot.close()
            throw e
        }
    }

    private fun readable(cache: DiskCache, file: Path): ArtworkLimits.Kind? = try {
        val kind = cache.fileSystem.source(file).buffer().use { ArtworkBounds.kind(it) }
        val bounds = cache.fileSystem.source(file).buffer().use { ArtworkBounds.read(it.inputStream()) }
        kind.takeIf { bounds != null && ArtworkLimits.dimensionsAcceptable(bounds.first, bounds.second) }
    } catch (e: IOException) {
        null
    }

    /** Writes the validated bytes to the cache (when allowed) and serves them from there, or straight from memory if the cache will not take them. */
    private fun save(key: String, downloaded: Downloaded): SourceFetchResult {
        val snapshot = if (cache != null && options.diskCachePolicy.writeEnabled) write(cache, key, downloaded.bytes) else null
        val source = if (snapshot != null) ImageSource(snapshot.data, cache!!.fileSystem, key, snapshot)
        else ImageSource(Buffer().write(downloaded.bytes), options.fileSystem)
        return SourceFetchResult(source, downloaded.kind.mime, DataSource.NETWORK)
    }

    private fun write(cache: DiskCache, key: String, bytes: ByteArray): DiskCache.Snapshot? {
        val editor = cache.openEditor(key) ?: return null
        return try {
            cache.fileSystem.write(editor.data) { write(bytes) }
            editor.commitAndOpenSnapshot()
        } catch (e: IOException) {
            editor.abort()
            null
        } catch (e: Throwable) {
            editor.abort()
            throw e
        }
    }

    class Factory(private val api: ServerApi, private val io: CoroutineDispatcher) : Fetcher.Factory<ArtworkRef> {
        override fun create(data: ArtworkRef, options: Options, imageLoader: ImageLoader): Fetcher =
            ArtworkFetcher(data, options, api, imageLoader.diskCache, io)
    }

    private companion object {
        const val TAG = "Artwork"

        /** `ImageFormat.Webp` in the Jellyfin 12.4.0 reference: decoded by Android 10 and later with no extra library. */
        const val FORMAT = "Webp"
    }
}
