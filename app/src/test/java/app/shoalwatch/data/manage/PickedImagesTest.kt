package app.shoalwatch.data.manage

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/** Preparing a picture from the photo picker (plan decision 8): bounded, scaled, re-encoded as JPEG; anything else refused. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PickedImagesTest {
    private val uri = Uri.parse("content://media/picker/0/1")

    private fun png(w: Int, h: Int, colour: Int = Color.RED): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(colour) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun prepare(bytes: ByteArray, kind: ImageKind = ImageKind.PRIMARY) =
        runBlocking { PickedImages({ ByteArrayInputStream(bytes) }).prepare(uri, kind) }

    private fun refused(block: () -> Unit): PictureRefused.Reason {
        try {
            block()
        } catch (e: PictureRefused) {
            return e.reason
        }
        fail("expected a refusal")
        error("unreachable")
    }

    private fun size(jpeg: ByteArray): Pair<Int, Int> {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, o)
        return o.outWidth to o.outHeight
    }

    /** A JPEG of [w] x [h] stored pixels whose EXIF says to show it turned ([orientation] 6: 90 degrees clockwise), as a phone camera writes. */
    private fun turnedJpeg(w: Int, h: Int, orientation: Int): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val plain = ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
        // TIFF header (big-endian), one IFD entry: Orientation (0x0112), SHORT, count 1, value; no next IFD.
        val tiff = intArrayOf(0x4D, 0x4D, 0, 0x2A, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation, 0, 0, 0, 0, 0, 0)
            .map { it.toByte() }.toByteArray()
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val length = payload.size + 2
        val app1 = byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + payload
        return plain.copyOfRange(0, 2) + app1 + plain.copyOfRange(2, plain.size)
    }

    @Test fun aPhotoTakenSidewaysComesOutTheWayItIsShown() {
        val out = prepare(turnedJpeg(400, 200, 6))
        assertEquals("turned upright, as the gallery shows it", 200 to 400, size(out.jpeg))
        assertEquals(200, out.width)
        assertEquals(400, out.height)
    }

    @Test fun aLargePictureIsScaledToTwoThousandOnItsLongSideAndBecomesAJpeg() {
        val out = prepare(png(3000, 4500))
        assertEquals(0xFF.toByte(), out.jpeg[0])
        assertEquals(0xD8.toByte(), out.jpeg[1])
        assertEquals(1333 to 2000, size(out.jpeg))
        assertEquals(1333, out.width)
    }

    @Test fun aBackdropMayBeLarger() {
        assertEquals(3840 to 2160, size(prepare(png(5120, 2880), ImageKind.BACKDROP).jpeg))
    }

    @Test fun aSmallPictureKeepsItsSize() {
        assertEquals(400 to 600, size(prepare(png(400, 600)).jpeg))
    }

    @Test fun transparencyIsLaidOnWhite() {
        val out = prepare(png(10, 10, Color.TRANSPARENT))
        val decoded = BitmapFactory.decodeByteArray(out.jpeg, 0, out.jpeg.size)
        val p = decoded.getPixel(5, 5)
        assertTrue("near white, not black", Color.red(p) > 240 && Color.green(p) > 240 && Color.blue(p) > 240)
    }

    @Test fun somethingThatIsNotAPictureIsRefused() {
        assertEquals(PictureRefused.Reason.NOT_A_PICTURE, refused { prepare("<html>not a picture</html>".toByteArray()) })
        assertEquals(PictureRefused.Reason.NOT_A_PICTURE, refused { prepare(ByteArray(0)) })
    }

    @Test fun aHugeDeclaredSizeIsRefusedBeforeAnyPixelIsDecoded() {
        // A PNG header claiming 20,000 x 20,000 with no pixels behind it.
        val header = png(1, 1).copyOf()
        // IHDR width and height are big-endian at bytes 16..23.
        fun put(at: Int, v: Int) { header[at] = (v ushr 24).toByte(); header[at + 1] = (v ushr 16).toByte(); header[at + 2] = (v ushr 8).toByte(); header[at + 3] = v.toByte() }
        put(16, 20_000); put(20, 20_000)
        val crc = java.util.zip.CRC32().apply { update(header, 12, 17) }.value.toInt()
        put(29, crc)
        assertEquals(PictureRefused.Reason.TOO_LARGE, refused { prepare(header) })
    }

    @Test fun aFileOverTheReadCapIsRefusedWithoutReadingItAll() {
        var read = 0L
        val endless = object : InputStream() {
            override fun read(): Int = 0.also { read++ }
            override fun read(b: ByteArray, off: Int, len: Int): Int { read += len; return len }
        }
        assertEquals(PictureRefused.Reason.TOO_LARGE, refused { runBlocking { PickedImages({ endless }).prepare(uri, ImageKind.PRIMARY) } })
        assertTrue(read <= PickedImages.MAX_READ_BYTES + 64 * 1024)
    }

    @Test fun anUnreadableChoiceIsSaidSo() {
        assertEquals(PictureRefused.Reason.UNREADABLE, refused { runBlocking { PickedImages({ null }).prepare(uri, ImageKind.PRIMARY) } })
        val broken = object : InputStream() { override fun read(): Int = throw IOException("gone") }
        assertEquals(PictureRefused.Reason.UNREADABLE, refused { runBlocking { PickedImages({ broken }).prepare(uri, ImageKind.PRIMARY) } })
    }

    @Test fun theTargetSizeKeepsTheShapeAndNeverGrows() {
        assertEquals(1333 to 2000, PickedImages.targetSize(3000, 4500, 2000))
        assertEquals(3840 to 2160, PickedImages.targetSize(5120, 2880, 3840))
        assertEquals(400 to 600, PickedImages.targetSize(400, 600, 2000))
        assertEquals("a sliver keeps a pixel", 2000 to 1, PickedImages.targetSize(16_000, 2, 2000))
        assertEquals("the largest allowed decodes small", 3840 to 3840, PickedImages.targetSize(10_000, 10_000, 3840))
    }

    @Test fun runningOutOfMemoryIsTooLargeNotACrash() {
        val bytes = png(10, 10)
        assertEquals(PictureRefused.Reason.TOO_LARGE, refused { PickedImages.prepareBytes(bytes, ImageKind.PRIMARY) { _, _ -> throw OutOfMemoryError() } })
    }
}
