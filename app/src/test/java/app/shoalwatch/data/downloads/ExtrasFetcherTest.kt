package app.shoalwatch.data.downloads

import app.shoalwatch.S1
import app.shoalwatch.TestMonotonic
import app.shoalwatch.data.db.DownloadRows
import app.shoalwatch.data.db.downloads.DownloadKey
import app.shoalwatch.data.db.downloads.DownloadSegmentEntity
import app.shoalwatch.data.db.library.LibraryStore
import app.shoalwatch.data.db.library.LocalChangeLog
import app.shoalwatch.data.images.ArtworkTestBase
import app.shoalwatch.data.images.PinnedArtwork
import app.shoalwatch.data.library.Item
import app.shoalwatch.data.library.ItemImages
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.playback.AudioTrack
import app.shoalwatch.data.playback.FakePlaybackGateway
import app.shoalwatch.data.playback.MediaSegment
import app.shoalwatch.data.playback.SegmentKind
import app.shoalwatch.data.playback.SubtitleKind
import app.shoalwatch.data.playback.SubtitleTrack
import app.shoalwatch.testId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** Tracks, subtitle files, skip markers and pinned pictures kept with a download (fake gateway; MockWebServer for pictures). */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExtrasFetcherTest : ArtworkTestBase() {
    private val gateway = FakePlaybackGateway()
    private lateinit var fake: FakeVolumes
    internal lateinit var paths: DownloadPaths
    internal lateinit var pinned: PinnedArtwork
    internal lateinit var extras: ExtrasFetcher
    private lateinit var folder: File
    private val pictures = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile private var picture: (RecordedRequest) -> MockResponse = { image(bitmapBytes(), "image/jpeg") }

    private val seriesId = testId("show")
    private val seasonId = testId("season1")
    private val film = item
    private val downloadKey get() = DownloadKey(S1, film)

    private fun item(id: String, kind: ItemKind, images: ItemImages, seriesId: String? = null, seasonId: String? = null) = Item(
        id = id, kind = kind, name = "x", sortName = null, parentId = null, seriesId = seriesId, seriesName = null, seasonId = seasonId,
        indexNumber = 1, parentIndexNumber = 1, overview = null, runtimeMs = null, year = null, premiereDate = null,
        communityRating = null, officialRating = null, childCount = null, images = images,
    )

    @Before fun setUpExtras() {
        fake = FakeVolumes(tmp.newFolder("downloads"))
        paths = DownloadPaths(fake.volumes)
        pinned = PinnedArtwork(db.downloadDao(), paths, Dispatchers.IO)
        extras = ExtrasFetcher(gateway, api, db.itemDao(), db.downloadDao(), paths, pinned, Dispatchers.IO)
        folder = paths.itemFolder(downloadKey, StorageKind.INTERNAL, null)!!.also { it.mkdirs() }
        DownloadRows.download(db.openHelper.writableDatabase, S1, film)
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse { pictures += request; return picture(request) }
        }
    }

    private fun shown(vararg items: Item) = runBlocking {
        LibraryStore(db, LocalChangeLog(TestMonotonic())).writeItems(session, items.map { ItemWithUserData(it, null) }, Instant.now(), requestedAtSeq = 0)
    }

    private val audio = listOf(AudioTrack(1, "English", "eng", "aac", 2, isDefault = true), AudioTrack(2, "French", "fre", "ac3", 6, isDefault = false))
    private val srt = SubtitleTrack(3, "English", "eng", "subrip", SubtitleKind.TEXT, false, false, false, external = true, fileFormat = "srt")
    private val ass = SubtitleTrack(4, "Japanese", "jpn", "ass", SubtitleKind.STYLED_TEXT, false, false, true, external = false, fileFormat = "ass")
    private val pgs = SubtitleTrack(5, "English forced", "eng", "pgssub", SubtitleKind.PICTURE, true, false, false, external = false)

    private fun tracks(quality: DownloadQuality, subtitles: List<SubtitleTrack> = listOf(srt, ass, pgs), audioTracks: List<AudioTrack> = audio) =
        runBlocking { extras.tracks(session, downloadKey, folder, "src1", audioTracks, subtitles, quality) }

    private fun saved() = runBlocking { db.downloadDao().tracks(S1, film) }

    @Test fun anOriginalSavesTheTwoTextSubtitleFilesAndListsThePictureOneInStream() {
        gateway.subtitleFiles[3] = "1\n00:00:01,000 --> 00:00:02,000\nHi\n".toByteArray()
        gateway.subtitleFiles[4] = "[Script Info]\n".toByteArray()
        tracks(DownloadQuality.ORIGINAL)
        val rows = saved()
        assertEquals(listOf(TrackType.AUDIO to 1, TrackType.AUDIO to 2, TrackType.SUBTITLE to 3, TrackType.SUBTITLE to 4, TrackType.SUBTITLE to 5),
            rows.map { it.type to it.streamIndex })
        assertEquals(listOf(true, true, false), rows.filter { it.type == TrackType.SUBTITLE }.map { it.fileSaved })
        assertEquals(SubtitleKind.PICTURE, rows.last().kind)
        assertEquals(listOf("subtitle:3.srt", "subtitle:4.ass"), gateway.calls.filter { it.startsWith("subtitle") })
        assertTrue(File(folder, "sub-3.srt").readText().contains("Hi"))
        assertTrue(File(folder, "sub-4.ass").exists())
        assertEquals("no part file is left", listOf("sub-3.srt", "sub-4.ass"), folder.listFiles()!!.map { it.name }.filter { it.startsWith("sub-") }.sorted())
        assertEquals(6, rows.first { it.streamIndex == 2 }.channels)
    }

    @Test fun aConversionKeepsOneAudioTrackAndNoPictureSubtitle() {
        gateway.subtitleFiles[3] = "1\n".toByteArray()
        gateway.subtitleFiles[4] = "[Script Info]\n".toByteArray()
        tracks(DownloadQuality.HIGH)
        val rows = saved()
        assertEquals(listOf(TrackType.AUDIO to 1, TrackType.SUBTITLE to 3, TrackType.SUBTITLE to 4), rows.map { it.type to it.streamIndex })
    }

    @Test fun aSubtitleThatFailsIsNotSavedAndTheRestAre() {
        gateway.subtitleFiles[4] = "[Script Info]\n".toByteArray() // 3 is missing: the gateway answers SERVER_NOT_FOUND
        tracks(DownloadQuality.ORIGINAL)
        assertEquals(mapOf(3 to false, 4 to true, 5 to false), saved().filter { it.type == TrackType.SUBTITLE }.associate { it.streamIndex to it.fileSaved })
        assertFalse(File(folder, "sub-3.srt").exists())
    }

    @Test fun atMostThirtySubtitlesAreKept() {
        val many = (0 until 40).map { srt.copy(index = 100 + it) }
        tracks(DownloadQuality.ORIGINAL, subtitles = many)
        assertEquals(30, saved().count { it.type == TrackType.SUBTITLE })
    }

    @Test fun theSkipMarkersAreSaved() {
        gateway.segmentAnswer = listOf(MediaSegment(SegmentKind.INTRO, 1_000, 3_000), MediaSegment(SegmentKind.OUTRO, 9_000, 10_000))
        runBlocking { extras.segments(session, downloadKey) }
        assertEquals(
            listOf(DownloadSegmentEntity(S1, film, SegmentKind.INTRO, 1_000, 3_000), DownloadSegmentEntity(S1, film, SegmentKind.OUTRO, 9_000, 10_000)),
            runBlocking { db.downloadDao().segments(S1, film) },
        )
        gateway.segmentsFail = app.shoalwatch.core.AppError.of(app.shoalwatch.core.AppError.Kind.OFFLINE)
        runBlocking { extras.segments(session, downloadKey) } // best effort: the download still plays without them
        assertEquals(2, runBlocking { db.downloadDao().segments(S1, film) }.size)
    }

    @Test fun theArtworkOfEpisodesTheirSeasonAndSeriesIsSavedOnce() {
        val series = item(seriesId, ItemKind.SERIES, ItemImages("sp", "sb", null, null))
        val season = item(seasonId, ItemKind.SEASON, ItemImages("zp", null, null, null), seriesId = seriesId)
        val episodes = (1..6).map { item(testId("e$it"), ItemKind.EPISODE, ItemImages("e$it", null, "t$it", "sp"), seriesId, seasonId) }
        shown(series, season, *episodes.toTypedArray())
        runBlocking { extras.artwork(session, episodes.map { it.id }) }
        val asked = pictures.map { it.requestUrl!!.pathSegments.let { p -> p[1] to p[3] } }
        assertEquals("6 primaries + 6 thumbs + the season's primary + the series' primary and backdrop", 15, asked.size)
        assertEquals(asked.size, asked.toSet().size)
        assertEquals(listOf(seriesId to "Primary", seriesId to "Backdrop"), asked.filter { it.first == seriesId })
        for (r in pictures) {
            val width = if (r.requestUrl!!.pathSegments[3] == "Backdrop") "1280" else "720"
            assertEquals(width, r.requestUrl!!.queryParameter("maxWidth"))
            assertEquals(setOf("tag", "maxWidth", "quality", "format"), r.requestUrl!!.queryParameterNames)
        }
        runBlocking { extras.artwork(session, episodes.map { it.id }) }
        assertEquals("what is already pinned with the same tag is skipped", 15, pictures.size)
        val names = File(fake.internalBase, "downloads/$S1/art").listFiles()!!.map { it.name }
        assertEquals(15, names.size)
        assertTrue("only the id, the slot and the tag: $names", names.all { Regex("[0-9a-f]{32}-(PRIMARY|BACKDROP|THUMB)-[A-Za-z0-9]{1,64}\\.img").matches(it) })
    }

    @Test fun aPictureThatFailsACheckIsNotSaved() {
        val a = item(testId("a"), ItemKind.MOVIE, ItemImages("svg", null, null, null))
        val b = item(testId("b"), ItemKind.MOVIE, ItemImages("tail", null, null, null))
        val c = item(testId("c"), ItemKind.MOVIE, ItemImages("good", null, null, null))
        shown(a, b, c)
        picture = { r ->
            when (r.requestUrl!!.queryParameter("tag")) {
                "svg" -> MockResponse().setHeader("Content-Type", "image/svg+xml").setBody("<svg xmlns='http://www.w3.org/2000/svg'/>")
                "tail" -> image(bitmapBytes() + ByteArray(64) { 7 }, "image/jpeg")
                else -> image(bitmapBytes(), "image/jpeg")
            }
        }
        runBlocking { extras.artwork(session, listOf(a.id, b.id, c.id)) }
        assertEquals(mapOf(c.id to "good"), listOf(a, b, c).associate { it.id to runBlocking { db.downloadDao().pinnedArtwork(S1, it.id) }[ArtworkSlot.PRIMARY] }.filterValues { it != null })
        assertEquals(1, File(fake.internalBase, "downloads/$S1/art").listFiles()!!.size)
    }

    @Test fun anItemTheSessionWasNotShownGetsNoPictures() {
        runBlocking { extras.artwork(session, listOf(testId("unseen"))) }
        assertEquals(0, pictures.size)
    }
}
