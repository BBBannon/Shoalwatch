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

    @Before fun openLoader() {
        loader = ArtworkLoader.build(context, api, tmp.newFolder("artwork"))
    }

    @After fun closeLoader() {
        loader.shutdown()
    }

    protected fun request(data: Any, widthPx: Int? = 300, memory: Boolean = false): ImageRequest =
        ImageRequest.Builder(context).data(data)
            .size(if (widthPx == null) Size(Dimension.Undefined, Dimension.Undefined) else Size(widthPx, widthPx * 3 / 2))
            .memoryCachePolicy(if (memory) CachePolicy.ENABLED else CachePolicy.DISABLED)
            .build()

    protected fun load(data: Any, widthPx: Int? = 300): ImageResult = runBlocking { loader.execute(request(data, widthPx)) }

    protected fun loaded(data: Any, widthPx: Int? = 300): Image = (load(data, widthPx) as SuccessResult).image
    protected fun failed(data: Any, widthPx: Int? = 300): Throwable = (load(data, widthPx) as ErrorResult).throwable

    protected fun cached(ref: ArtworkRef, bucket: Int) = loader.diskCache!!.openSnapshot(ArtworkLimits.cacheKey(ref, bucket))?.also { it.close() }

    protected fun bitmapBytes(w: Int = 40, h: Int = 60, format: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(20, 120, 110)) }
        return ByteArrayOutputStream().also { check(bitmap.compress(format, 90, it)) }.toByteArray()
    }

    protected fun image(bytes: ByteArray, type: String) = MockResponse().setResponseCode(200).setHeader("Content-Type", type).setBody(Buffer().write(bytes))

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
