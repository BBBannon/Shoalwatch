package app.shoalwatch.data.images

import android.graphics.BitmapFactory
import coil3.ImageLoader
import coil3.decode.BitmapFactoryDecoder
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import coil3.request.Options
import coil3.size.Precision
import coil3.size.Scale
import coil3.size.Size
import okio.BufferedSource
import java.io.InputStream

/** Why a picture was refused. Carries nothing from the response, and its message is fixed. */
class ArtworkRejectedException(val reason: Reason) : RuntimeException("Artwork refused.") {
    enum class Reason { CONTENT_TYPE, NOT_AN_IMAGE, INCOMPLETE, DIMENSIONS }
}

/** Something other than an [ArtworkRef] was handed to the artwork loader; nothing was fetched. */
class ModelNotAllowedException : RuntimeException("Only artwork can be loaded.")

/** Reads what an image declares about itself from its header only: no pixel is allocated. */
internal object ArtworkBounds {
    /** The declared (width, height), or null if they cannot be read. */
    fun read(stream: InputStream): Pair<Int, Int>? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(stream, null, options)
        return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }

    /** The kind the first bytes of [source] show, without consuming them. */
    fun kind(source: BufferedSource): ArtworkLimits.Kind? {
        val peek = source.peek()
        val head = if (peek.request(HEAD)) peek.readByteArray(HEAD) else peek.readByteArray()
        return ArtworkLimits.sniff(head)
    }

    private const val HEAD = 12L
}

/**
 * The only image decoder, a guard in front of Coil's own `BitmapFactoryDecoder`: it checks the first bytes and the
 * declared dimensions BEFORE any pixel is allocated (a refusal is an [ArtworkRejectedException]), then decodes with an
 * explicit size so the bitmap is never larger than twice the width bucket, whatever size the caller asked for or the server sent.
 * No animated-image decoder is registered anywhere, so an animated image can only ever be a still frame, and GIF is refused outright.
 */
object ArtworkDecoder {
    class Factory(private val delegate: Decoder.Factory = BitmapFactoryDecoder.Factory()) : Decoder.Factory {
        override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder = Decoder {
            val source = result.source.source()
            if (ArtworkBounds.kind(source) == null) throw ArtworkRejectedException(ArtworkRejectedException.Reason.NOT_AN_IMAGE)
            val bounds = ArtworkBounds.read(source.peek().inputStream())
            if (bounds == null || !ArtworkLimits.dimensionsAcceptable(bounds.first, bounds.second)) {
                throw ArtworkRejectedException(ArtworkRejectedException.Reason.DIMENSIONS)
            }
            val bucket = ArtworkLimits.bucketFor(options.size)
            // FIT inside a bucket-wide box, never upscaled: Coil's sample size then keeps the width within [bucket, 2 x bucket).
            val bounded = options.copy(size = Size(bucket, bucket * MAX_ASPECT), scale = Scale.FIT, precision = Precision.INEXACT)
            val decoded: DecodeResult? = delegate.create(result, bounded, imageLoader)?.decode()
            decoded
        }
    }

    private const val MAX_ASPECT = 4
}

/** Refuses any request whose model is not an [ArtworkRef], before Coil's own mappers and fetchers (file, content, resource, asset, data...) can see it. */
internal class ArtworkOnlyInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult =
        if (chain.request.data is ArtworkRef) chain.proceed() else ErrorResult(null, chain.request, ModelNotAllowedException())
}
