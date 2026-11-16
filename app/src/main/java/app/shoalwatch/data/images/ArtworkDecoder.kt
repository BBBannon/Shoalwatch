package app.shoalwatch.data.images

import android.graphics.BitmapFactory
import androidx.core.graphics.drawable.toDrawable
import app.shoalwatch.data.images.ArtworkRejectedException.Reason
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.request.bitmapConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * The only image decoder. It checks the first bytes and the declared dimensions BEFORE any pixel is allocated, then decodes
 * with a power-of-two sample size chosen by [ArtworkLimits.sampleSize]: never wider than twice the width bucket (whatever size
 * the screen asked for or the server sent) and never more than [ArtworkLimits.MAX_DECODED_PIXELS] pixels. A picture that
 * cannot meet that is refused. At most [CONCURRENT_DECODES] decodes run at once. Any failure here (not cancellation) also
 * has the picture removed from the cache, so the next request downloads it again instead of failing until its tag changes.
 * No animated-image decoder is registered anywhere.
 */
object ArtworkDecoder {
    class Factory : Decoder.Factory {
        private val permits = Semaphore(CONCURRENT_DECODES)

        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder = Decoder {
            try {
                decode(result, options)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                (result.source.metadata as? ArtworkSourceInfo)?.evict?.invoke()
                throw e
            }
        }

        private suspend fun decode(result: SourceFetchResult, options: Options): DecodeResult {
            val source = result.source.source()
            if (ArtworkBounds.kind(source) == null) throw ArtworkValidation.refuse(Reason.NOT_AN_IMAGE)
            val bounds = ArtworkBounds.read(source.peek().inputStream())
            if (bounds == null || !ArtworkLimits.dimensionsAcceptable(bounds.first, bounds.second)) throw ArtworkValidation.refuse(Reason.DIMENSIONS)
            val sample = ArtworkLimits.sampleSize(bounds.first, bounds.second, ArtworkLimits.bucketFor(options.size))
                ?: throw ArtworkValidation.refuse(Reason.DIMENSIONS)
            return permits.withPermit {
                runInterruptible {
                    val decodeOptions = BitmapFactory.Options().apply {
                        inSampleSize = sample
                        inMutable = false
                        inPreferredConfig = options.bitmapConfig
                    }
                    val bitmap = BitmapFactory.decodeStream(source.inputStream(), null, decodeOptions) ?: throw ArtworkValidation.refuse(Reason.DECODE)
                    bitmap.density = options.context.resources.displayMetrics.densityDpi
                    DecodeResult(bitmap.toDrawable(options.context.resources).asImage(), isSampled = sample > 1)
                }
            }
        }
    }

    const val CONCURRENT_DECODES = 2
}
