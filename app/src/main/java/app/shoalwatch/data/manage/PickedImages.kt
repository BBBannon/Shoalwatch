package app.shoalwatch.data.manage

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer

/** Why a picture from the phone was not used. The screen says it in plain words; nothing about the file is logged. */
class PictureRefused(val reason: Reason) : Exception("Picture refused.") {
    enum class Reason { NOT_A_PICTURE, TOO_LARGE, UNREADABLE }

    override fun toString() = "PictureRefused($reason)"
}

/**
 * A picture chosen in the Android photo picker, made ready to upload on the phone (plan decision 8, roadmap D3). The picker hands
 * the app a content address it may read once, so no storage permission is needed. The file is read through [open] (the content
 * resolver) up to [MAX_READ_BYTES]; its size is read from the header first (no pixels), refused over [MAX_SIDE] a side or
 * [MAX_PIXELS] in all (the size as shown, after the photo's EXIF orientation); it is decoded upright with `ImageDecoder` straight
 * to a long side of at most 2,000 px (3,840 for a backdrop), laid on white (a transparent PNG would otherwise turn black) and
 * re-encoded as JPEG, which also drops EXIF and every other piece of metadata (location included). The result is at most 10 MB;
 * running out of memory on the way is refused as too large.
 */
class PickedImages(private val open: (Uri) -> InputStream?, private val io: CoroutineDispatcher = Dispatchers.IO) {

    suspend fun prepare(uri: Uri, kind: ImageKind): PreparedImage = withContext(io) {
        val bytes = try {
            open(uri)?.use(::readCapped) ?: throw PictureRefused(PictureRefused.Reason.UNREADABLE)
        } catch (e: IOException) {
            throw PictureRefused(PictureRefused.Reason.UNREADABLE)
        } catch (e: SecurityException) { // the picker's permission was withdrawn
            throw PictureRefused(PictureRefused.Reason.UNREADABLE)
        }
        prepareBytes(bytes, kind)
    }

    companion object {
        const val MAX_READ_BYTES = 40 * 1024 * 1024
        const val MAX_SIDE = 16_000
        const val MAX_PIXELS = 120_000_000L
        private const val QUALITY = 90
        private const val MAX_OUT_BYTES = 10 * 1024 * 1024

        fun longSide(kind: ImageKind) = if (kind == ImageKind.BACKDROP) 3_840 else 2_000

        private fun readCapped(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > MAX_READ_BYTES) throw PictureRefused(PictureRefused.Reason.TOO_LARGE)
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        }

        /** The size to decode to: [w] x [h] with its long side brought down to [target] (never up), each side at least 1 px. */
        internal fun targetSize(w: Int, h: Int, target: Int): Pair<Int, Int> = when {
            maxOf(w, h) <= target -> w to h
            w >= h -> target to maxOf(1, (h.toLong() * target / w).toInt())
            else -> maxOf(1, (w.toLong() * target / h).toInt()) to target
        }

        /**
         * [decode] is the seam for tests. Running out of memory anywhere here (a decode, the JPEG) is "too large", not a crash: the
         * bitmap is at most the target size, so it can only happen on a phone already short of memory.
         */
        internal fun prepareBytes(bytes: ByteArray, kind: ImageKind, decode: (ByteArray, ImageKind) -> Bitmap = ::decodeUpright): PreparedImage {
            val bitmap = try {
                decode(bytes, kind)
            } catch (e: OutOfMemoryError) {
                throw PictureRefused(PictureRefused.Reason.TOO_LARGE)
            }
            try {
                for (quality in listOf(QUALITY, 80, 70)) {
                    val out = ByteArrayOutputStream()
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) throw PictureRefused(PictureRefused.Reason.NOT_A_PICTURE)
                    val jpeg = out.toByteArray()
                    if (jpeg.size <= MAX_OUT_BYTES) return PreparedImage(jpeg, bitmap.width, bitmap.height)
                }
                throw PictureRefused(PictureRefused.Reason.TOO_LARGE)
            } catch (e: OutOfMemoryError) {
                throw PictureRefused(PictureRefused.Reason.TOO_LARGE)
            } finally {
                bitmap.recycle()
            }
        }

        /**
         * One decode, upright and at the target size: `ImageDecoder` applies the EXIF orientation (BitmapFactory does not, so a phone
         * photo came out sideways) and reports the turned size in the header, which is checked before any pixel is decoded. It then
         * decodes straight to the target size (no full-size bitmap, no scaled copy) in software memory, and the post-processor lays
         * the picture on white in place (a transparent PNG would otherwise turn black as a JPEG).
         */
        private fun decodeUpright(bytes: ByteArray, kind: ImageKind): Bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                val w = info.size.width
                val h = info.size.height
                if (w <= 0 || h <= 0) throw PictureRefused(PictureRefused.Reason.NOT_A_PICTURE)
                if (w > MAX_SIDE || h > MAX_SIDE || w.toLong() * h > MAX_PIXELS) throw PictureRefused(PictureRefused.Reason.TOO_LARGE)
                val (tw, th) = targetSize(w, h, longSide(kind))
                if (tw != w || th != h) decoder.setTargetSize(tw, th)
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setPostProcessor { canvas ->
                    canvas.drawColor(Color.WHITE, PorterDuff.Mode.DST_OVER)
                    PixelFormat.OPAQUE
                }
            }
        } catch (e: IOException) { // ImageDecoder.DecodeException: not a picture it can read
            throw PictureRefused(PictureRefused.Reason.NOT_A_PICTURE)
        }
    }
}
