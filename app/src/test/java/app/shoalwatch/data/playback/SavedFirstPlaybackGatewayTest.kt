package app.shoalwatch.data.playback

import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.db.downloads.DownloadSegmentEntity
import app.shoalwatch.data.db.downloads.DownloadTrackEntity
import app.shoalwatch.data.downloads.DownloadPaths
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.FakeVolumes
import app.shoalwatch.data.downloads.SavedMedia
import app.shoalwatch.data.downloads.SavedRows
import app.shoalwatch.data.downloads.StorageKind
import app.shoalwatch.data.downloads.TrackType
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.BEN
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Saved subtitles and segments for a saved copy the session may play; the server's for everything else. */
internal class SavedFirstPlaybackGatewayTest {
    @get:Rule val tmp = TemporaryFolder()
    private val item = PlaybackFakes.ITEM
    private val server = FakePlaybackGateway()
    private lateinit var paths: DownloadPaths
    private lateinit var folder: File
    private lateinit var media: SavedMedia
    private val tracks = mutableListOf<DownloadTrackEntity>()
    private val rows = object : SavedRows {
        override suspend fun tracks(key: DownloadKey) = tracks.toList()
        override suspend fun segments(key: DownloadKey) = listOf(DownloadSegmentEntity(key.serverId, key.itemId, SegmentKind.OUTRO, 10, 20))
    }
    private lateinit var gateway: SavedFirstPlaybackGateway

    @Before fun setUp() {
        paths = DownloadPaths(FakeVolumes(tmp.root).volumes)
        folder = paths.itemFolder(DownloadKey(BEN.serverId, item), StorageKind.INTERNAL, null)!!.also { it.mkdirs() }
        media = SavedMedia(BEN.serverId, item, folder, paths.mediaFile(folder, "mkv"), false, DownloadQuality.ORIGINAL, PlaybackFakes.SOURCE)
        tracks += DownloadTrackEntity(BEN.serverId, item, TrackType.SUBTITLE, 2, "English", "eng", "subrip", SubtitleKind.TEXT, "srt",
            false, false, false, false, null, fileSaved = true)
        gateway = SavedFirstPlaybackGateway(server, { s, id -> media.takeIf { s == BEN && id == item } }, rows, paths, Dispatchers.Unconfined)
        server.subtitleFiles[2] = "from the server".toByteArray()
        server.segmentAnswer = listOf(MediaSegment(SegmentKind.INTRO, 1, 2))
    }

    @Test fun aSavedItemWithTheMatchingMediaSourceGetsTheSavedBytes() = runBlocking {
        paths.subtitleFile(folder, 2, "srt").writeText("saved")
        assertArrayEquals("saved".toByteArray(), gateway.subtitleFile(BEN, item, PlaybackFakes.SOURCE, 2, "srt"))
        assertEquals(listOf(MediaSegment(SegmentKind.OUTRO, 10, 20)), gateway.segments(BEN, item))
        assertTrue(server.calls.isEmpty())
    }

    @Test fun everythingElseIsDelegated() = runBlocking {
        paths.subtitleFile(folder, 2, "srt").writeText("saved")
        assertEquals("another profile", "from the server", String(gateway.subtitleFile(AMY, item, PlaybackFakes.SOURCE, 2, "srt")))
        assertEquals("another media source", "from the server", String(gateway.subtitleFile(BEN, item, "f".repeat(32), 2, "srt")))
        assertEquals("another format", "from the server", String(gateway.subtitleFile(BEN, item, PlaybackFakes.SOURCE, 2, "vtt")))
        assertEquals(listOf(MediaSegment(SegmentKind.INTRO, 1, 2)), gateway.segments(AMY, item))
        assertEquals(listOf("subtitle:2.srt", "subtitle:2.srt", "subtitle:2.vtt", "segments:$item"), server.calls)
    }

    @Test fun aTrackWhoseFileWasNotSavedIsTheServers() = runBlocking {
        tracks[0] = tracks[0].copy(fileSaved = false)
        assertEquals("from the server", String(gateway.subtitleFile(BEN, item, PlaybackFakes.SOURCE, 2, "srt")))
    }

    @Test fun anOversizedSavedFileIsRefused() = runBlocking {
        paths.subtitleFile(folder, 2, "srt").writeBytes(ByteArray(SavedFirstPlaybackGateway.MAX_BYTES + 1))
        try {
            gateway.subtitleFile(BEN, item, PlaybackFakes.SOURCE, 2, "srt")
            fail("an oversized file must be refused")
        } catch (e: AppError) {
            assertEquals(AppError.Kind.SERVER_ERROR, e.kind)
        }
        assertTrue("and the server is not asked instead", server.calls.isEmpty())
    }

    @Test fun aMissingSavedFileIsUnreadableNotTheServers() = runBlocking {
        try {
            gateway.subtitleFile(BEN, item, PlaybackFakes.SOURCE, 2, "srt")
            fail("a missing file must be refused")
        } catch (e: AppError) {
            assertEquals(AppError.Kind.SERVER_ERROR, e.kind)
        }
    }
}
