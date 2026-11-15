package app.shoalwatch.data.playback

import app.shoalwatch.TestMonotonic
import app.shoalwatch.data.api.MediaRoute
import app.shoalwatch.data.library.Freshness
import app.shoalwatch.data.library.ItemDetail
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.library.SeriesDetail
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.browse.AMY
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.FakeLibrary
import app.shoalwatch.ui.browse.id
import app.shoalwatch.ui.browse.item
import app.shoalwatch.ui.browse.userData
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent

/**
 * The controller over fakes at every seam, under virtual time: a real [PlaybackResolver] on a [FakePlaybackGateway] (answering
 * the direct-play fixture unless a test queues others), [FakePlayerEngine], [FakePlaybackReports], [FakeLibrary] and a real
 * [EpisodeNavigator] and [ActivePlayback] (Ben active). EP1 and EP2 are episodes 1 and 2 of one season, 20 minutes each.
 */
internal abstract class PlaybackControllerTestBase {
    val session: Session = BEN
    val library = FakeLibrary()
    val gateway = FakePlaybackGateway()
    val reports = FakePlaybackReports()
    val engine = FakePlayerEngine()
    val clock = TestMonotonic(0)
    val signedIn = mutableSetOf(BEN, AMY)
    val activePlayback = ActivePlayback({ it in signedIn }, { it() }).also { it.sessionChanged(BEN) }
    var autoplay = true
    val routesMade = mutableListOf<Session>()
    private val routes: suspend (Session) -> MediaRoute = { s -> routesMade += s; PlaybackFakes.route(s) }
    private val caps = DeviceCapabilities(
        video = setOf(VideoCodec.H264), audio = setOf("aac"), h264MaxLevel = 41, hevcMain10 = false, vp9Profile2 = false, av1Main10 = false,
        decoderHdr = emptyMap(), dolbyVisionProfiles = emptySet(), display = emptySet(),
    )

    val ep1 = id(101)
    val ep2 = id(102)
    val runtime = 1_200_000L
    private val season = ItemWithUserData(item(61, ItemKind.SEASON, "Season 1", seriesId = id(50), index = 1), null)

    fun episode(n: Int, positionMs: Long = 0, runtimeMs: Long? = runtime) = ItemWithUserData(
        item(n, ItemKind.EPISODE, "Episode $n", seriesId = id(50), seriesName = "Series 50", seasonId = id(61), index = n - 100, parentIndex = 1, runtimeMs = runtimeMs),
        userData(positionMs = positionMs),
    )

    fun saved(entry: ItemWithUserData) { library.itemFlow(session, entry.item.id).value = ItemDetail(entry, Freshness.Fresh) }

    init {
        saved(episode(101))
        saved(episode(102))
        library.seriesFlow(session, id(50), id(61)).value =
            SeriesDetail(null, listOf(season), listOf(episode(101), episode(102)), id(61), Freshness.Fresh)
        repeat(4) { gateway.answer(fixtureInfo("direct-play.json")) }
    }

    fun answerOnly(vararg names: String) { gateway.infos.clear(); names.forEach { gateway.answer(fixtureInfo(it)) } }

    fun TestScope.controller() = PlaybackController(
        session, engine, PlaybackResolver(gateway, { caps }, routes, StandardTestDispatcher(testScheduler)), reports, gateway, library,
        EpisodeNavigator(library), activePlayback, backgroundScope, backgroundScope, clock, { autoplay },
    )

    /** Opened, loaded, and playing, with a known duration. */
    fun TestScope.openAndPlay(c: PlaybackController, itemId: String = ep1, from: StartFrom = StartFrom.RESUME) {
        c.open(itemId, from); runCurrent()
        engine.durationMs = runtime
        engine.startPlaying(); runCurrent()
    }

    /** The engine at [ms] for one controller tick. */
    fun TestScope.at(ms: Long) { engine.positionMs = ms; advanceTimeBy(250); runCurrent() }

    fun TestScope.advanceTimeBy(ms: Long) = testScheduler.advanceTimeBy(ms)

    val playbackInfos get() = gateway.calls.count { it.startsWith("playbackInfo") }
    val stopCalls get() = reports.calls.filter { it.startsWith("stopped") }
}
