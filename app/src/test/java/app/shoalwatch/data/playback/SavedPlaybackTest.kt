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
import app.shoalwatch.data.library.Freshness
import app.shoalwatch.data.library.ItemDetail
import app.shoalwatch.data.playback.PlaybackState.Phase
import app.shoalwatch.data.playback.api.PlaybackGateway
import app.shoalwatch.data.playback.engine.EngineEvent
import app.shoalwatch.data.playback.engine.EngineFailure
import app.shoalwatch.data.playback.engine.SubtitleFrame
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.browse.AMY
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Offline playback through the unchanged controller (Milestone 4 Task 13): a verified copy of episode 1 for Ben, a saved SRT
 * subtitle (index 3) and an intro marker, over the real resolver, [SavedFirstPlaybackGateway] and [RoutedPlaybackReports].
 */
internal class SavedPlaybackTest : PlaybackControllerTestBase() {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var paths: DownloadPaths
    private lateinit var media: SavedMedia
    private val local = FakePlaybackReports()
    private val rechecked = mutableListOf<Pair<Session, String>>()
    private val lookups = mutableListOf<Session>()
    /** The copy is there for Ben only (the real lookup's visibility and sharing rules are SavedCopiesTest's). */
    private var available = true

    private val rows = object : SavedRows {
        val tracks = mutableListOf<DownloadTrackEntity>()
        val segments = mutableListOf<DownloadSegmentEntity>()
        override suspend fun tracks(key: DownloadKey) = tracks.filter { it.itemId == key.itemId }
        override suspend fun segments(key: DownloadKey) = segments.filter { it.itemId == key.itemId }
    }

    private fun plan(s: Session, id: String): SavedPlan? {
        lookups += s
        if (!available || s != session || id != ep1) return null
        return SavedPlan(media, SavedPlans.plan(id, media, rows.tracks, runtime, hasSegments = rows.segments.isNotEmpty()))
    }

    override val savedLookup: suspend (Session, String) -> SavedPlan? = { s, id -> plan(s, id) }
    override val savedFailed: (Session, String) -> Unit = { s, id -> rechecked += s to id }
    override val playerGateway: PlaybackGateway by lazy { SavedFirstPlaybackGateway(gateway, { s, id -> plan(s, id)?.media }, rows, paths, Dispatchers.Unconfined) }
    override val playerReports: PlaybackReports get() = RoutedPlaybackReports(reports, local)

    @Before fun saveACopy() {
        val fake = FakeVolumes(tmp.root)
        paths = DownloadPaths(fake.volumes)
        val folder = paths.itemFolder(DownloadKey(session.serverId, ep1), StorageKind.INTERNAL, null)!!.also { it.mkdirs() }
        val file = paths.mediaFile(folder, "mp4").apply { writeBytes(ByteArray(64)) }
        paths.subtitleFile(folder, 3, "srt").writeText("1\n00:00:01,000 --> 00:00:09,000\nHello from the phone\n")
        media = SavedMedia(session.serverId, ep1, folder, file, hls = false, quality = DownloadQuality.ORIGINAL, mediaSourceId = PlaybackFakes.SOURCE)
        rows.tracks += track(TrackType.AUDIO, 1, null, null, saved = false, default = true)
        rows.tracks += track(TrackType.SUBTITLE, 3, SubtitleKind.TEXT, "srt", saved = true)
        rows.tracks += track(TrackType.SUBTITLE, 4, SubtitleKind.TEXT, "srt", saved = false) // its file failed to save: not offered
        rows.segments += DownloadSegmentEntity(session.serverId, ep1, SegmentKind.INTRO, 1_000, 3_000)
        gateway.failWith = AppError.of(AppError.Kind.OFFLINE) // the server is out of reach unless a test says otherwise
    }

    private fun track(type: TrackType, index: Int, kind: SubtitleKind?, format: String?, saved: Boolean, default: Boolean = false) =
        DownloadTrackEntity(session.serverId, ep1, type, index, "Track $index", "eng", if (type == TrackType.AUDIO) "aac" else "subrip",
            kind, format, false, false, default, false, if (type == TrackType.AUDIO) 2 else null, fileSaved = saved)

    private val lastLoad get() = engine.loads.last().first

    @Test fun withAVerifiedCopyAndTheServerOfflineItLoadsTheSavedFileAndAsksTheServerNothing() = runTest {
        val c = controller(); openAndPlay(c)
        assertSame(media, lastLoad.saved)
        assertNull(lastLoad.route)
        assertEquals(0, playbackInfos)
        assertTrue("no route asked for", routesMade.isEmpty())
        assertEquals(PlayMethod.LOCAL, c.state.value.method)
        assertEquals(Phase.PLAYING, c.state.value.phase)
        assertEquals("the subtitle without a saved file is not offered", listOf(3), c.state.value.subtitleTracks.map { it.index })
    }

    @Test fun theSavedSubtitleLoadsFromThePhone() = runTest {
        val c = controller(); openAndPlay(c)
        c.selectSubtitle(3); runCurrent()
        at(2_000)
        assertEquals(SubtitleChoice.On(3, SubtitleDelivery.FILE), c.state.value.subtitle)
        assertNull(c.state.value.subtitleNotice)
        assertNotEquals(SubtitleFrame.NONE, c.cues.value)
        assertTrue("never asked the server", gateway.calls.none { it.startsWith("subtitle") })
    }

    @Test fun skipIntroAppearsFromTheSavedSegments() = runTest {
        val c = controller(); openAndPlay(c)
        at(1_500)
        assertEquals(SegmentKind.INTRO, c.state.value.segmentNow?.kind)
        assertTrue(gateway.calls.none { it.startsWith("segments") })
    }

    @Test fun aStopIsKeptOnThePhoneAndNothingReachesTheServer() = runTest {
        val c = controller(); openAndPlay(c); at(300_000)
        c.end(null); runCurrent()
        assertEquals("started@0", local.calls.first())
        assertEquals("stopped@300000", local.calls.last())
        assertEquals(PlayMethod.LOCAL, local.stops.single().method)
        assertTrue("nothing to the server's reports", reports.calls.isEmpty())
        assertTrue("nothing to the server", gateway.reports.isEmpty() && gateway.calls.none { it == "ping" })
    }

    @Test fun aFinishedStopSaysFinished() = runTest {
        val c = controller(); openAndPlay(c)
        at(1_078_000); at(1_081_000) // across 90% of the 20-minute runtime, by playback
        c.end(null); runCurrent()
        assertEquals("stopped@1081000 finished", local.calls.last())
    }

    @Test fun switchingAccountMidPlayEndsItExactlyAsBefore() = runTest {
        val c = controller(); openAndPlay(c); at(60_000)
        activePlayback.sessionChanged(AMY); runCurrent()
        assertTrue(c.state.value.closed)
        assertTrue(engine.released)
        assertEquals(listOf(session), local.stopSessions)
        assertEquals("stopped@60000", local.calls.last())
    }

    @Test fun aProfileWithoutVisibilityGetsAStreamAttemptNeverTheFile() = runTest {
        activePlayback.sessionChanged(AMY)
        library.itemFlow(AMY, ep1).value = ItemDetail(episode(101), Freshness.Fresh)
        val c = controllerFor(AMY, backgroundScope)
        c.open(ep1, StartFrom.RESUME); runCurrent()
        assertTrue("the lookup was asked as Amy", AMY in lookups)
        assertEquals(1, playbackInfos)
        assertTrue(engine.loads.isEmpty())
        assertEquals(AppError.Kind.OFFLINE, c.state.value.error)
    }

    @Test fun aDecoderFailureReChecksTheCopyOnceAndThenStreams() = runTest {
        val c = controller(); openAndPlay(c); at(600_000)
        gateway.failWith = null
        answerOnly("transcode.json")
        engine.isPlaying = false; engine.emit(EngineEvent.Failed(EngineFailure.DECODER)); runCurrent()
        assertEquals(listOf(session to ep1), rechecked)
        assertEquals(1, playbackInfos)
        assertNull("streams now", lastLoad.saved)
        assertNotNull(lastLoad.route)
        assertEquals(600_000L, engine.loadedAt)
        assertEquals(PlayMethod.TRANSCODE, c.state.value.method)
        assertEquals("the saved play is stopped on the phone", "stopped@600000", local.calls.last())
        engine.startPlaying(); runCurrent()
        engine.isPlaying = false; engine.emit(EngineEvent.Failed(EngineFailure.DECODER)); runCurrent()
        assertEquals(AppError.Kind.PLAYBACK_FAILED, c.state.value.error)
        assertEquals("re-checked once only", 1, rechecked.size)
    }

    @Test fun aDecoderFailureOfflineReChecksAndShowsTheOfflineMessage() = runTest {
        val c = controller(); openAndPlay(c)
        engine.isPlaying = false; engine.emit(EngineEvent.Failed(EngineFailure.DECODER)); runCurrent()
        assertEquals(1, rechecked.size)
        assertEquals(AppError.Kind.OFFLINE, c.state.value.error)
    }

    @Test fun aQualityChangeKeepsTheSavedCopy() = runTest {
        val c = controller(); openAndPlay(c)
        c.setQuality(Quality.MBPS_4); runCurrent()
        assertSame(media, lastLoad.saved)
        assertEquals(0, playbackInfos)
    }

    @Test fun anAudioLanguageDisagreementKeepsThePlayersTrack() = runTest {
        rows.tracks += track(TrackType.AUDIO, 2, null, null, saved = false)
        val c = controller(); openAndPlay(c)
        engine.audioLanguages = listOf("fra") // the stream names another language at that position
        c.selectAudio(2); runCurrent()
        engine.emit(EngineEvent.Ready); runCurrent()
        assertEquals(0, playbackInfos)
        assertEquals(1, engine.loads.size)
        assertEquals(2, c.state.value.audioIndex)
    }

    @Test fun withNoCopyItStreamsAsBefore() = runTest {
        available = false
        gateway.failWith = null
        val c = controller(); openAndPlay(c)
        assertEquals(1, playbackInfos)
        assertNull(lastLoad.saved)
        assertEquals(PlayMethod.DIRECT_PLAY, c.state.value.method)
    }

    @Test fun anInvalidVersionDoesNotSkipTheSavedCopy() = runTest {
        val c = controller(); c.open(ep1, StartFrom.RESUME, null, "not-a-version"); runCurrent()
        assertSame(media, lastLoad.saved)
        assertEquals(0, playbackInfos)
    }

    @Test fun aDifferentValidVersionIsAskedOfTheServerNotTheSavedCopy() = runTest {
        gateway.failWith = null
        val c = controller(); c.open(ep1, StartFrom.RESUME, null, "fedcba9876543210fedcba9876543210"); runCurrent()
        assertEquals(1, playbackInfos)
        assertEquals("fedcba9876543210fedcba9876543210", gateway.bodies.last().mediaSourceId)
    }

    @Test fun theControllerFileIsStillAtMost400Lines() {
        val file = listOf("src/main/java", "app/src/main/java").map { File(it, "app/shoalwatch/data/playback/PlaybackController.kt") }.first { it.isFile }
        assertTrue(file.readLines().size <= 400)
    }
}
