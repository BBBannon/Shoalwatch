package app.shoalwatch.data.images

import android.graphics.BitmapFactory
import app.shoalwatch.core.AppError
import app.shoalwatch.data.servers.Session
import coil3.decode.ImageSource
import coil3.intercept.Interceptor
import coil3.request.ErrorResult
import coil3.request.ImageResult
import kotlinx.coroutines.CancellationException
import okio.BufferedSource
import java.io.InputStream

/** Why a picture was refused. Carries nothing from the response, and its message is fixed. */
class ArtworkRejectedException(val reason: Reason) : RuntimeException("Artwork refused.") {
    enum class Reason { CONTENT_TYPE, NOT_AN_IMAGE, INCOMPLETE, DIMENSIONS, ANIMATED, DECODE }
}

/** Something other than an [ArtworkRef] was handed to the artwork loader; nothing was fetched. */
class ModelNotAllowedException : RuntimeException("Only artwork can be loaded.")

/** Travels with a picture's [ImageSource] so the decoder can have a picture that will not decode removed from the cache. */
internal class ArtworkSourceInfo(val evict: () -> Unit) : ImageSource.Metadata()

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
 * First in Coil's chain, before its own mappers, memory cache and fetchers (file, content, resource, asset, data...):
 * 1. a request whose model is not an [ArtworkRef] fails with [ModelNotAllowedException];
 * 2. a ref whose session is no longer a signed-in account (per [signedIn], a token-free in-memory check) fails as
 *    `NOT_SIGNED_IN`, so a picture saved in memory or on disk is never shown to a signed-out session.
 */
internal class ArtworkOnlyInterceptor(private val signedIn: suspend (Session) -> Boolean) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val ref = chain.request.data as? ArtworkRef ?: return ErrorResult(null, chain.request, ModelNotAllowedException())
        val allowed = try {
            signedIn(ref.session)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        return if (allowed) chain.proceed() else ErrorResult(null, chain.request, AppError.of(AppError.Kind.NOT_SIGNED_IN))
    }
}
