package app.shoalwatch.data.playback.engine

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import app.shoalwatch.core.AppError
import app.shoalwatch.data.downloads.DownloadPathsTest
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.SavedMedia
import app.shoalwatch.data.playback.MediaPaths
import app.shoalwatch.data.playback.PlaybackFakes
import app.shoalwatch.net.BlockedRequestException
import app.shoalwatch.ui.browse.BEN
import java.io.File
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@androidx.annotation.OptIn(UnstableApi::class)
@RunWith(RobolectricTestRunner::class)
class ItemBytesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val root by lazy { tmp.newFolder("downloads", SERVER) }
    private val folder by lazy { File(root, ITEM).apply { mkdirs() } }
    private val bytes = ByteArray(1_000) { it.toByte() }
    private val remote = FakeRemote()
    private var current: EngineItem? = null
    private val source by lazy { ItemBytes.Factory({ remote }) { current }.createDataSource() }

    private fun saved() = EngineItem(SavedMedia(SERVER, ITEM, folder, File(folder, "media.mp4"), hls = false, quality = DownloadQuality.ORIGINAL, mediaSourceId = ITEM))
    private fun streamed() = EngineItem(PlaybackFakes.route(BEN), MediaPaths.directStream(PlaybackFakes.ITEM, PlaybackFakes.SOURCE, null))
    private fun uri(file: File): Uri = Uri.parse(file.toURI().toString())

    private fun refused(uri: Uri) {
        try {
            source.open(DataSpec(uri))
            fail("opened")
        } catch (e: IOException) {
            val blocked = generateSequence<Throwable>(e) { it.cause }.filterIsInstance<BlockedRequestException>().firstOrNull()
            assertEquals(AppError.Kind.FORBIDDEN, blocked?.error?.kind)
            assertTrue("a fixed message", e.message == "Not this item's bytes.")
        }
        source.close()
    }

    @Test fun aSavedItemsOwnFileOpensAndReadsFromAPosition() {
        val file = File(folder, "media.mp4").apply { writeBytes(bytes) }
        current = saved()
        assertEquals(900L, source.open(DataSpec.Builder().setUri(uri(file)).setPosition(100).build()))
        val buffer = ByteArray(900)
        var got = 0
        while (got < 900) { val n = source.read(buffer, got, 900 - got); if (n < 0) break; got += n }
        assertArrayEquals(bytes.copyOfRange(100, 1_000), buffer)
        assertEquals(androidx.media3.common.C.RESULT_END_OF_INPUT, source.read(buffer, 0, 1))
        source.close()
        assertEquals(50L, source.open(DataSpec.Builder().setUri(uri(file)).setPosition(10).setLength(50).build()))
        source.close()
        val hls = File(folder, "hls").apply { mkdirs() }
        File(hls, "seg-00000.ts").writeBytes(bytes)
        assertEquals("a file deeper in its folder", 1_000L, source.open(DataSpec(uri(File(hls, "seg-00000.ts")))))
        source.close()
        assertEquals("nothing went to the network", 0, remote.opens)
    }

    @Test fun aSavedItemCannotReachOutsideItsFolder() {
        File(folder, "media.mp4").writeBytes(bytes)
        val other = File(root, OTHER).apply { mkdirs() }
        File(other, "media.mp4").writeBytes(bytes)
        current = saved()
        refused(Uri.parse(folder.toURI().toString() + "../$OTHER/media.mp4")) // ../ escape
        refused(uri(File(folder, "../$OTHER/media.mp4")))
        refused(uri(File(other, "media.mp4"))) // another item's folder
        refused(uri(folder)) // the folder itself
        refused(uri(File(folder, "missing.mp4")))
        refused(Uri.parse("https://evil.example/Items/x/Download")) // an http URI while the item is saved
        refused(Uri.parse("content://app.shoalwatch/x"))
        refused(Uri.parse("media.mp4")) // no scheme
        assertEquals(0, remote.opens)
    }

    @Test fun aLinkInsideTheFolderThatLeadsOutsideIsRefused() {
        val away = tmp.newFolder("away")
        File(away, "seg-00000.ts").writeBytes(bytes)
        assumeTrue("this machine cannot make a link", DownloadPathsTest.link(File(folder, "hls"), away))
        current = saved()
        refused(uri(File(File(folder, "hls"), "seg-00000.ts")))
    }

    @Test fun aRemoteItemOpensHttpButNoFile() {
        val file = File(folder, "media.mp4").apply { writeBytes(bytes) }
        current = streamed()
        refused(uri(file))
        assertEquals(7L, source.open(DataSpec(Uri.parse("https://jf.example/Videos/x/stream"))))
        source.close()
        assertEquals(1, remote.opens)
    }

    @Test fun withNoCurrentItemEverythingFails() {
        val file = File(folder, "media.mp4").apply { writeBytes(bytes) }
        current = null
        refused(uri(file))
        refused(Uri.parse("https://jf.example/Videos/x/stream"))
        assertEquals(0, remote.opens)
    }

    @Test fun theCurrentItemIsReadAtEveryOpen() {
        val file = File(folder, "media.mp4").apply { writeBytes(bytes) }
        current = saved()
        source.open(DataSpec(uri(file)))
        source.close()
        current = streamed()
        refused(uri(file))
    }

    @Test fun theSavedSourceRefusesALinkedFileAndIsNotANetworkSource() {
        val away = tmp.newFolder("elsewhere")
        val target = File(away, "real.mp4").apply { writeBytes(bytes) }
        val link = File(folder, "media.mp4")
        val linked = try { java.nio.file.Files.createSymbolicLink(link.toPath(), target.toPath()); true } catch (e: Exception) { false }
        assumeTrue("this machine cannot make a file link", linked)
        try {
            SavedMediaDataSource().open(DataSpec(uri(link)), link)
            fail("opened a link")
        } catch (e: IOException) {
            assertEquals("Not a saved file.", e.cause?.message)
        }
    }

    private class FakeRemote : DataSource {
        var opens = 0
        override fun addTransferListener(transferListener: TransferListener) = Unit
        override fun open(dataSpec: DataSpec): Long { opens++; return 7 }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = -1
        override fun getUri(): Uri? = null
        override fun close() = Unit
    }

    private companion object {
        const val SERVER = "0123456789abcdef0123456789abcdef"
        const val ITEM = "fedcba9876543210fedcba9876543210"
        const val OTHER = "00000000000000000000000000000001"
    }
}
