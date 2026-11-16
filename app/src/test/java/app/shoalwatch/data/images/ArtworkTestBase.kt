package app.shoalwatch.data.images

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.data.api.ServerApiTestBase
import app.shoalwatch.data.servers.Server
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.S1
import app.shoalwatch.testId
import coil3.ImageLoader
import coil3.Image
import coil3.request.CachePolicy
import coil3.request.ErrorResult
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.size.Dimension
import coil3.size.Size
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** The real loader, `ServerApi`, OkHttp and disk cache against MockWebServer, serving real small images and hostile payloads. */
abstract class ArtworkTestBase : ServerApiTestBase() {
    @get:Rule val tmp = TemporaryFolder()
    protected val context: Context get() = ApplicationProvider.getApplicationContext()
    protected lateinit var loader: ImageLoader
    protected val item = testId("film")
    protected fun ref(tag: String = "tag1", type: ArtworkType = ArtworkType.PRIMARY, id: String = item) = ArtworkRef.of(session, id, type, tag)!!

    protected lateinit var caches: ArtworkDiskCaches
    protected val signedIn: suspend (app.shoalwatch.data.servers.Session) -> Boolean = { s ->
        store.awaitWarmed()
        store.cachedToken(s.serverId, s.userId) != null
    }

    /** Tests that need to watch the disk cache override this to wrap it. */
    protected open fun makeCaches(directory: java.io.File) = ArtworkDiskCaches(directory)

    @Before fun openLoader() {
        caches = makeCaches(tmp.newFolder("artwork"))
        loader = ArtworkLoader.build(context, api, signedIn, caches)
    }

    protected fun diskOf(ref: ArtworkRef) = caches.forServer(ref.session.serverId)

    @After fun closeLoader() {
        loader.shutdown()
    }

    protected fun request(data: Any, widthPx: Int? = 300, memory: Boolean = false, disk: Boolean = true): ImageRequest =
        ImageRequest.Builder(context).data(data)
            .size(if (widthPx == null) Size(Dimension.Undefined, Dimension.Undefined) else Size(widthPx, widthPx * 3 / 2))
            .memoryCachePolicy(if (memory) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .diskCachePolicy(if (disk) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .precision(coil3.size.Precision.INEXACT)
            .build()

    protected fun load(data: Any, widthPx: Int? = 300, memory: Boolean = false, disk: Boolean = true): ImageResult =
        runBlocking { loader.execute(request(data, widthPx, memory, disk)) }

    protected fun loaded(data: Any, widthPx: Int? = 300, memory: Boolean = false, disk: Boolean = true): Image =
        when (val result = load(data, widthPx, memory, disk)) {
            is SuccessResult -> result.image
            is ErrorResult -> throw AssertionError("expected an image but got ${result.throwable.javaClass.name}: ${result.throwable.message}", result.throwable)
        }
    protected fun failed(data: Any, widthPx: Int? = 300, memory: Boolean = false, disk: Boolean = true): Throwable = (load(data, widthPx, memory, disk) as ErrorResult).throwable

    protected fun cached(ref: ArtworkRef, bucket: Int) = diskOf(ref).openSnapshot(ArtworkLimits.cacheKey(ref, bucket))?.also { it.close() }

    protected fun bitmapBytes(w: Int = 40, h: Int = 60, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 120, 110)) }
        return ByteArrayOutputStream().also { check(bitmap.compress(format, 90, it)) }.toByteArray()
    }

    /** A PNG of random pixels, so its image data is large enough to corrupt in the middle. */
    protected fun noisyPng(w: Int, h: Int): ByteArray {
        val random = java.util.Random(7)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(w * h) { random.nextInt() or (0xFF shl 24) }, 0, w, 0, 0, w, h)
        return ByteArrayOutputStream().also { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }.toByteArray()
    }

    protected fun image(bytes: ByteArray, type: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", type).setBody(Buffer().write(bytes))

    /** A real PNG of solid colour, [w] x [h], streamed through the compressor so a huge one costs little memory. */
    protected fun pngSolid(w: Int, h: Int): ByteArray {
        fun int(v: Long) = ByteArray(4) { (v shr (24 - 8 * it)).toByte() }
        fun chunk(type: String, data: ByteArray): ByteArray {
            val body = type.toByteArray(Charsets.US_ASCII) + data
            return int(data.size.toLong()) + body + int(CRC32().apply { update(body) }.value)
        }
        val ihdr = int(w.toLong()) + int(h.toLong()) + byteArrayOf(8, 2, 0, 0, 0)
        val deflater = Deflater(Deflater.BEST_SPEED)
        val compressed = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        fun drain() { while (true) { val n = deflater.deflate(buffer); if (n == 0) break; compressed.write(buffer, 0, n) } }
        val row = ByteArray(1 + 3 * w) // filter byte 0, then black pixels
        repeat(h) { deflater.setInput(row); drain() }
        deflater.finish(); while (!deflater.finished()) drain()
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        return sig + chunk("IHDR", ihdr) + chunk("IDAT", compressed.toByteArray()) + chunk("IEND", ByteArray(0))
    }

    /** A PNG that DECLARES [w] x [h] pixels but holds almost nothing (a decompression bomb's header). */
    protected fun pngDeclaring(w: Int, h: Int): ByteArray {
        fun chunk(type: String, data: ByteArray): ByteArray {
            val body = type.toByteArray(Charsets.US_ASCII) + data
            val crc = CRC32().apply { update(body) }.value
            fun int(v: Long) = ByteArray(4) { (v shr (24 - 8 * it)).toByte() }
            return int(data.size.toLong()) + body + int(crc)
        }
        fun int(v: Int) = ByteArray(4) { (v shr (24 - 8 * it)).toByte() }
        val ihdr = int(w) + int(h) + byteArrayOf(8, 2, 0, 0, 0)
        val deflater = Deflater().apply { setInput(ByteArray(8)); finish() }
        val idat = ByteArray(64).let { out -> out.copyOf(deflater.deflate(out)) }
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        return sig + chunk("IHDR", ihdr) + chunk("IDAT", idat) + chunk("IEND", ByteArray(0))
    }

    /** Points the saved server (and the verified address) at a different MockWebServer: the same server, reached another way. */
    protected fun moveServerTo(other: okhttp3.mockwebserver.MockWebServer) {
        val addr = ServerAddress.parse("http://127.0.0.1:${other.port}")
        runBlocking { store.upsertServer(Server(S1, "Den", "10.10.3", addr, null, true, null, null, emptyMap())) }
        selector.answer = { app.shoalwatch.data.servers.Connection.Online(addr, isHome = true) }
    }
}
