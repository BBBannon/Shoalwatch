package app.shoalwatch.data.downloads

import app.shoalwatch.data.db.downloads.DownloadTrackEntity
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.playback.PlayMethod
import app.shoalwatch.data.playback.PlaybackFakes
import app.shoalwatch.data.playback.SubtitleKind
import app.shoalwatch.data.servers.Session
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The one lookup of a saved copy the player may open: COMPLETE, visible by the sharing rule, mounted, and the recorded size. */
class SavedCopiesTest : DownloadQueueTestBase() {
    private val film get() = item("film")
    private lateinit var copies: SavedCopies
    private val card by lazy { tmp.newFolder("card") }

    @Before fun setUpCopies() { copies = SavedCopies(db.offlineLibraryDao(), db.downloadDao().savedRows(), paths, Dispatchers.Unconfined) }

    /** A's verified original of [item] on [storage], [bytes] long on disk. */
    private fun saved(item: Item = film, storage: StorageKind = StorageKind.INTERNAL, bytes: Int = 100, recorded: Long = 100): File {
        val volumeId = if (storage == StorageKind.REMOVABLE) "CARD-1".also { fake.card(it, card) } else null
        seen(s1u1, item)
        runBlocking {
            store.add(s1u1, listOf(newDownload(item).let { n -> app.shoalwatch.data.db.downloads.DownloadStore.NewDownload(n.itemId, n.kind, n.seriesId, n.seasonId, n.quality, storage, volumeId, n.runtimeMs, null) }))
            store.update(key(item)) { it.copy(state = DownloadState.COMPLETE, container = "mkv", mediaSourceId = PlaybackFakes.SOURCE, totalBytes = recorded, doneBytes = recorded) }
        }
        val folder = paths.itemFolder(key(item), storage, volumeId)!!.also { it.mkdirs() }
        return paths.mediaFile(folder, "mkv").apply { writeBytes(ByteArray(bytes)) }
    }

    private fun media(s: Session = s1u1, item: Item = film) = runBlocking { copies.media(s, item.id) }

    @Test fun theOwnersVerifiedCopyIsOfferedWithItsLocalPlan() {
        val file = saved()
        runBlocking {
            db.downloadDao().replaceTracks(s1u1.serverId, film.id, listOf(
                DownloadTrackEntity(s1u1.serverId, film.id, TrackType.SUBTITLE, 3, "English", "eng", "subrip", SubtitleKind.TEXT, "srt", false, false, false, false, null, fileSaved = true),
            ))
        }
        val m = media()!!
        assertEquals(file.canonicalPath, m.entry.canonicalPath)
        val plan = runBlocking { copies.plan(s1u1, film.id) }!!
        assertEquals(PlayMethod.LOCAL, plan.plan.method)
        assertEquals(listOf(3), plan.plan.subtitleTracks.map { it.index })
        assertEquals(PlaybackFakes.SOURCE, plan.plan.mediaSourceId)
    }

    @Test fun aCopyNotYetCompleteIsNotOffered() {
        saved()
        setState(film, DownloadState.VERIFYING)
        assertNull(media())
    }

    @Test fun anotherProfileGetsItOnlyWithSharingOnAndItsOwnConfirmedPermission() = runBlocking {
        saved()
        seen(s1u2, film)
        assertNull("sharing off", media(s1u2))
        store.setSharing(s1u1.serverId, true)
        assertNull("sharing on, but its permission is not confirmed", media(s1u2))
        confirm(s1u2, film)
        assertNotNull(media(s1u2))
        assertNull("never across servers", media(Session(s2u1.serverId, s1u2.userId)))
    }

    @Test fun aProfileWithoutAVisibilityRecordNeverGetsTheFile() = runBlocking {
        saved()
        store.setSharing(s1u1.serverId, true)
        confirm(s1u2, film) // permission alone is not enough
        assertNull(media(s1u2))
    }

    @Test fun aCopyOnACardThatIsOutIsNotOffered() {
        saved(storage = StorageKind.REMOVABLE)
        assertNotNull(media())
        fake.unmount("CARD-1")
        assertNull(media())
    }

    @Test fun aFileOfAnotherLengthThanRecordedIsNotOffered() {
        saved(bytes = 99, recorded = 100)
        assertNull(media())
    }

    @Test fun aConversionNeedsItsPlaylistAndEverySegment() {
        seen(s1u1, film)
        runBlocking {
            store.add(s1u1, listOf(newDownload(film, DownloadQuality.MEDIUM)))
            store.update(key(film)) { it.copy(state = DownloadState.COMPLETE, container = "hls", mediaSourceId = PlaybackFakes.SOURCE, segmentsTotal = 3, segmentsDone = 3) }
        }
        val hls = paths.hlsFolder(folder(film)).also { it.mkdirs() }
        paths.localPlaylist(hls).writeText("#EXTM3U")
        (0 until 2).forEach { paths.segmentFile(hls, it).writeBytes(ByteArray(10)) }
        assertNull("a segment is missing", media())
        paths.segmentFile(hls, 2).writeBytes(ByteArray(10))
        val m = media()!!
        assertTrue(m.hls)
        assertEquals(DownloadQuality.MEDIUM, m.quality)
    }

    @Test fun aMalformedIdIsNothing() {
        saved()
        assertNull(runBlocking { copies.media(s1u1, film.id.uppercase()) })
        assertNull(runBlocking { copies.media(s1u1, "../x") })
    }
}
