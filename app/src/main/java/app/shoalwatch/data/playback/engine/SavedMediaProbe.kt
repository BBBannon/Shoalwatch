package app.shoalwatch.data.playback.engine

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.MetadataRetriever
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import app.shoalwatch.core.AppError
import app.shoalwatch.core.AppLog
import app.shoalwatch.data.downloads.SavedMedia
import app.shoalwatch.net.BlockedRequestException
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import okhttp3.Call

/** What Media3 read from a saved copy: the tracks' MIME types (as Media3 names them) and the duration, when it has one. */
internal data class ProbeResult(val videoMimes: List<String>, val audioMimes: List<String>, val durationMs: Long?)

/** Null: Media3 could not read it (or not within the time allowed). */
internal fun interface PlayabilityProbe { suspend fun probe(media: SavedMedia): ProbeResult? }

/**
 * Media3's `MetadataRetriever` over a media source factory that reads through [ItemBytes] with the saved copy as the current item,
 * so it can read only that item's folder, and nothing over the network ([RefuseAll] is never reached: `ItemBytes` refuses an
 * http URI for a saved item first). The picture-only subtitle parsers, as in the player. Waits at most [timeoutMs] in all.
 */
@OptIn(UnstableApi::class)
internal class SavedMediaProbe(context: Context, private val timeoutMs: Long = TIMEOUT_MS) : PlayabilityProbe {
    private val appContext = context.applicationContext

    override suspend fun probe(media: SavedMedia): ProbeResult? {
        val item = EngineItem(media)
        val sources = DefaultMediaSourceFactory(ItemBytes.Factory(OkHttpDataSource.Factory(RefuseAll)) { item })
            .setSubtitleParserFactory(PictureSubtitles)
        return runInterruptible(Dispatchers.IO) {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            fun left() = (deadline - System.nanoTime()).coerceAtLeast(0)
            MetadataRetriever.Builder(appContext, MediaItems.build(item, null, null)).setMediaSourceFactory(sources).build().use { retriever ->
                val tracks = retriever.retrieveTrackGroups()
                val duration = retriever.retrieveDurationUs()
                try {
                    val groups = tracks.get(left(), TimeUnit.NANOSECONDS)
                    val durationUs = duration.get(left(), TimeUnit.NANOSECONDS)
                    val mimes = (0 until groups.length).flatMap { g -> groups[g].let { group -> (0 until group.length).mapNotNull { group.getFormat(it).sampleMimeType } } }
                    ProbeResult(
                        videoMimes = mimes.filter(MimeTypes::isVideo).distinct(),
                        audioMimes = mimes.filter(MimeTypes::isAudio).distinct(),
                        durationMs = durationUs.takeIf { it != C.TIME_UNSET && it > 0 }?.let { it / 1000 },
                    )
                } catch (e: TimeoutException) {
                    tracks.cancel(true)
                    duration.cancel(true)
                    AppLog.w(TAG, "saved copy probe timed out")
                    null
                } catch (e: ExecutionException) {
                    AppLog.w(TAG, "saved copy unreadable: ${e.cause?.javaClass?.simpleName}")
                    null
                }
            }
        }
    }

    /** The probe's network: none. */
    private object RefuseAll : Call.Factory {
        override fun newCall(request: okhttp3.Request): Call = throw BlockedRequestException(AppError.of(AppError.Kind.FORBIDDEN))
    }

    private companion object {
        const val TAG = "SavedMediaProbe"
        const val TIMEOUT_MS = 30_000L
    }
}
