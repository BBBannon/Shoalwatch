package app.shoalwatch.data.playback.engine

import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.HlsPlaylists
import app.shoalwatch.data.downloads.SavedMedia
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Real Media3 extractors on Milestone 3's 10 s test clip (h264 baseline + aac) and its 5 HLS segments. */
@RunWith(RobolectricTestRunner::class)
class SavedMediaProbeTest {
    @get:Rule val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val folder by lazy { tmp.newFolder(SERVER, ITEM) }

    private fun resource(name: String): ByteArray = checkNotNull(javaClass.classLoader!!.getResourceAsStream("media/$name")) { name }.use { it.readBytes() }

    private fun original(bytes: ByteArray) = SavedMedia(SERVER, ITEM, folder, File(folder, "media.mp4").apply { writeBytes(bytes) },
        hls = false, quality = DownloadQuality.ORIGINAL, mediaSourceId = ITEM)

    @Test fun theClipIsH264AndAacAndAboutTenSeconds() = runBlocking {
        val found = SavedMediaProbe(context).probe(original(resource("clip.mp4")))
        assertNotNull(found)
        assertEquals(listOf("video/avc"), found!!.videoMimes)
        assertEquals(listOf("audio/mp4a-latm"), found.audioMimes)
        assertTrue("about 10 s: ${found.durationMs}", found.durationMs!! in 9_500..10_500)
    }

    @Test fun theSavedSegmentsOfAConversionAreReadThroughTheLocalPlaylist() = runBlocking {
        val hls = File(folder, "hls").apply { mkdirs() }
        for (i in 0 until 5) File(hls, "seg-%05d.ts".format(i)).writeBytes(resource("hls/seg$i.ts"))
        val playlist = File(hls, "local.m3u8").apply { writeText(HlsPlaylists.writeLocal(List(5) { 2_000L })) }
        val found = SavedMediaProbe(context).probe(SavedMedia(SERVER, ITEM, folder, playlist, hls = true, quality = DownloadQuality.MEDIUM, mediaSourceId = ITEM))
        assertNotNull(found)
        assertEquals(listOf("video/avc"), found!!.videoMimes)
        assertEquals(listOf("audio/mp4a-latm"), found.audioMimes)
        assertTrue("about 10 s: ${found.durationMs}", found.durationMs!! in 9_500..10_500)
    }

    @Test fun aTruncatedFileGivesNullWithinTheTimeout() = runBlocking {
        val clip = resource("clip.mp4")
        val started = System.nanoTime()
        assertNull(SavedMediaProbe(context, timeoutMs = 5_000).probe(original(clip.copyOf(1_000))))
        assertTrue("within the timeout", (System.nanoTime() - started) / 1_000_000 < 6_000)
    }

    @Test fun aFileOutsideTheItemsFolderIsNeverRead() = runBlocking {
        val elsewhere = tmp.newFolder("elsewhere")
        val outside = File(elsewhere, "media.mp4").apply { writeBytes(resource("clip.mp4")) }
        val media = SavedMedia(SERVER, ITEM, folder, outside, hls = false, quality = DownloadQuality.ORIGINAL, mediaSourceId = ITEM)
        assertNull(SavedMediaProbe(context, timeoutMs = 5_000).probe(media))
    }

    private companion object {
        const val SERVER = "0123456789abcdef0123456789abcdef"
        const val ITEM = "fedcba9876543210fedcba9876543210"
    }
}
