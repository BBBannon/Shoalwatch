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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The one lookup of a saved copy the player may open: COMPLETE, visible by the sharing rule, mounted, and the recorded size. */
class SavedCopiesTest : DownloadHousekeepingTestBase() {
    private val film get() = item("film")
    private lateinit var copies: SavedCopies
    private val sdCard by lazy { tmp.newFolder("sd-card") }

    /** Whether the server can be reached now, and the sessions a re-ask was asked for (Tasks 13-16 review I-1). */
    private var reachable = false
    private val rechecks = mutableListOf<Session>()
    private var onRecheck: (Session) -> Unit = {}

    @Before fun setUpCopies() {
        copies = SavedCopies(
            db.offlineLibraryDao(), db.downloadDao().savedRows(), paths, Dispatchers.Unconfined,
            now = { clock }, reachable = { reachable }, recheck = { s -> rechecks += s; onRecheck(s) },
        )
    }

    /** A's verified original of [item] on [storage], [bytes] long on disk. */
    private fun saved(item: Item = film, storage: StorageKind = StorageKind.INTERNAL, bytes: Int = 100, recorded: Long = 100): File {
        val volumeId = if (storage == StorageKind.REMOVABLE) "CARD-1".also { fake.card(it, sdCard) } else null
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

    /** B (not an owner) was confirmed for A's shared film; [days] later, is the copy offered for playback? */
    private fun sharedAndConfirmed(): Unit = runBlocking {
        saved()
        seen(s1u2, film)
        store.setSharing(s1u1.serverId, true)
        confirm(s1u2, film)
    }

    private fun plan(s: Session) = runBlocking { copies.plan(s, film.id) }

    @Test fun aSharedCopyWhoseConfirmationIsOverAWeekOldIsNotPlayedWhileTheServerCanBeReachedUntilReAsked() {
        sharedAndConfirmed()
        reachable = true
        clock += 6 * DAY
        assertNotNull("six days old: still good", plan(s1u2))
        assertTrue("nothing to re-ask", rechecks.isEmpty())
        clock += 2 * DAY
        assertNull("eight days old and the server is there: stream instead", plan(s1u2))
        assertEquals("and it is re-asked", listOf(s1u2), rechecks)
        assertNotNull("the owner needs no confirmation, however old", plan(s1u1))
        confirm(s1u2, film) // the server said yes again
        assertNotNull(plan(s1u2))
        assertNotNull("subtitles of a copy already playing still load", media(s1u2))
    }

    @Test fun offlineAConfirmationThatCannotBeReAskedStillPlaysHoweverOld() {
        sharedAndConfirmed()
        reachable = false
        clock += 60 * DAY
        assertNotNull(plan(s1u2))
        assertTrue(rechecks.isEmpty())
    }

    @Test fun aChildWhoNeverOpensDownloadsLosesTheSharedCopyOnceTheServerSaysNo() {
        sharedAndConfirmed()
        reachable = true
        clock += 8 * DAY
        facts.allow(film.id, canDownload = false) // the admin turned the child's download permission off
        onRecheck = { s -> runBlocking { housekeeping.reconfirm(s) } } // what SharingRecheck runs
        assertNull("never LOCAL", plan(s1u2))
        assertFalse("the permission is gone", runBlocking { store.isConfirmed(key(film), s1u2.userId) })
        assertNull("and stays gone", plan(s1u2))
        assertNotNull("the owner keeps it", plan(s1u1))
    }

    private companion object { const val DAY = 24L * 60 * 60 * 1_000 }
}
