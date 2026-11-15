package app.shoalwatch.data.downloads

import app.shoalwatch.TestMonotonic
import app.shoalwatch.core.AppError
import app.shoalwatch.data.playback.PlayMethod
import app.shoalwatch.data.playback.PlaybackPlan
import app.shoalwatch.data.playback.PlaybackRequest
import app.shoalwatch.data.playback.Quality
import app.shoalwatch.data.playback.Resolved
import app.shoalwatch.data.playback.StreamSource
import app.shoalwatch.data.playback.VideoRange
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** A converted download, segment by segment, within one play session only; never two sessions' segments mixed. */
class HlsFetcherTest : FileFetcherTestBase() {
    /** Play sessions as the server cuts them: each has its own segment lengths (seconds); `recut` gives each new one a new shape. */
    private inner class HlsServer : Dispatcher() {
        val shapes = mutableMapOf<String, List<Double>>()
        val segmentRequests = CopyOnWriteArrayList<Pair<String, Int>>()
        val all = CopyOnWriteArrayList<RecordedRequest>()
        var dropAt: Int? = null
        var killAt: Int? = null
        var segment: (Int) -> MockResponse? = { null }
        var media: ((String) -> String)? = null

        override fun dispatch(request: RecordedRequest): MockResponse {
            all += request
            val path = request.requestUrl!!.encodedPath
            val sid = request.requestUrl!!.queryParameter("PlaySessionId")
            if (path == "/videos/$item/master.m3u8" && sid in shapes) {
                return MockResponse().setBody("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1\nhls/$sid/main.m3u8?api_key=LEAK\n")
            }
            val m = Regex("/videos/$item/hls/([0-9a-f]{32})/(.+)").matchEntire(path) ?: return MockResponse().setResponseCode(404)
            val session = m.groupValues[1]
            val shape = shapes[session] ?: return MockResponse().setResponseCode(404)
            val name = m.groupValues[2]
            if (name == "main.m3u8") return MockResponse().setBody(media?.invoke(session) ?: playlist(shape))
            val n = Regex("seg(\\d+)\\.ts").matchEntire(name)?.groupValues?.get(1)?.toInt() ?: return MockResponse().setResponseCode(404)
            segmentRequests += session to n
            if (n == killAt) return MockResponse().setResponseCode(404)
            if (n == dropAt) {
                dropAt = null
                return MockResponse().setBody(Buffer().write(ts(session, n))).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
            }
            segment(n)?.let { return it }
            return MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(ts(session, n)))
        }

        fun playlist(shape: List<Double>) = buildString {
            append("#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-VERSION:3\n#EXT-X-TARGETDURATION:3\n#EXT-X-MEDIA-SEQUENCE:0\n")
            shape.forEachIndexed { n, s -> append("#EXTINF:%.6f,\nseg$n.ts?api_key=LEAK\n".format(s)) }
            append("#EXT-X-ENDLIST\n")
        }
    }

    /** One 188-byte TS packet naming its session and index. */
    private fun ts(session: String, n: Int): ByteArray = (byteArrayOf(0x47) + "$session:$n".toByteArray()).copyOf(188)

    private val hls = HlsServer()
    private val clock = TestMonotonic()
    private lateinit var folder: File
    private val done = mutableListOf<Pair<Int, Int>>()

    @Before fun serveHls() {
        web.dispatcher = hls
        folder = part.parentFile!!
    }

    private fun session(n: Int) = "%032x".format(n)

    private fun resolved(sid: String, shape: List<Double> = List(5) { 2.0 }): Resolved {
        hls.shapes[sid] = shape
        val plan = PlaybackPlan(
            request = PlaybackRequest(item, Quality.MBPS_8), mediaSourceId = "src1", playSessionId = sid, method = PlayMethod.TRANSCODE,
            source = StreamSource("/videos/$item/master.m3u8", listOf("PlaySessionId" to sid), hls = true),
            audioTracks = emptyList(), subtitleTracks = emptyList(), audioIndex = null, runtimeMs = 10_000, videoRange = VideoRange.SDR,
            transcodeReasons = emptySet(), hasSegments = false, eTag = null,
        )
        return Resolved(plan, runBlocking { api.mediaRoute(session) })
    }

    private fun hlsFetcher(maxSegment: Long = 64L * 1024 * 1024) = HlsFetcher(Dispatchers.IO, clock, paths, maxSegment)

    private fun open(f: HlsFetcher, r: Resolved, runtimeMs: Long? = 10_000) = runBlocking { f.open(r, folder, runtimeMs) }

    private fun fetch(f: HlsFetcher, live: LiveConversion, from: Int) = runBlocking { f.fetch(live, folder, from) { d, t -> done += d to t } }

    private val hlsDir get() = File(folder, "hls")

    private fun segmentFiles() = hlsDir.listFiles()!!.map { it.name }.filter { it.startsWith("seg-") }.sorted()

    /** The local playlist names exactly the segment files there, and every one is from [sid]. */
    private fun assertOneSession(sid: String, count: Int) {
        val parsed = HlsPlaylists.parse(File(hlsDir, "local.m3u8").readText(), "/videos/local/local.m3u8") as HlsPlaylists.Parsed.Media
        assertEquals(count, parsed.segments.size)
        assertEquals((0 until count).map { "seg-%05d.ts".format(it) }, segmentFiles())
        for (n in 0 until count) assertEquals(ts(sid, n).toList(), File(hlsDir, "seg-%05d.ts".format(n)).readBytes().toList())
    }

    @Test fun masterThenMediaThenFiveSegmentsAndTheLocalPlaylistHasTheirDurations() {
        val f = hlsFetcher()
        val (live, result) = open(f, resolved(session(1)))
        assertEquals(FetchResult.Done, result)
        assertEquals(FetchResult.Done, fetch(f, live!!, 0))
        val parsed = HlsPlaylists.parse(File(hlsDir, "local.m3u8").readText(), "/videos/local/local.m3u8") as HlsPlaylists.Parsed.Media
        assertEquals(List(5) { 2_000L }, parsed.segments.map { it.durationMs })
        assertOneSession(session(1), 5)
        assertEquals((1..5).map { it to 5 }, done)
        assertEquals(clock.ms, live.startedAtMs)
        assertEquals("LiveConversion(<redacted>)", live.toString())
        for (r in hls.all) {
            assertFalse(r.path!!.contains("api_key", ignoreCase = true))
            assertTrue(r.getHeader("Authorization") != null)
        }
    }

    @Test fun aNetworkDropAtSegmentThreeResumesAtThreeInTheSamePlaySession() {
        hls.dropAt = 3
        val f = hlsFetcher()
        val live = open(f, resolved(session(1))).first!!
        assertEquals(FetchResult.Retry(AppError.Kind.NETWORK), fetch(f, live, 0))
        assertEquals(3, done.last().first)
        assertEquals(FetchResult.Done, fetch(f, live, 3))
        assertEquals(listOf(0, 1, 2, 3, 3, 4), hls.segmentRequests.map { it.second })
        assertEquals("one play session", setOf(session(1)), hls.segmentRequests.map { it.first }.toSet())
        assertOneSession(session(1), 5)
    }

    @Test fun aFreshOpenAfterProcessDeathStartsAgainFromZeroWithAStableServer() = afterRestart(List(5) { 2.0 })

    @Test fun aFreshOpenAfterProcessDeathStartsAgainFromZeroWithARecuttingServer() = afterRestart(List(4) { 2.5 })

    private fun afterRestart(newShape: List<Double>) {
        hls.dropAt = 3
        val first = hlsFetcher()
        val live = open(first, resolved(session(1))).first!!
        assertEquals(FetchResult.Retry(AppError.Kind.NETWORK), fetch(first, live, 0))
        assertEquals(3, segmentFiles().size)
        val second = hlsFetcher() // the process died: a new fetcher over the same folder, and a new play session
        val (fresh, result) = open(second, resolved(session(2), newShape))
        assertEquals(FetchResult.Done, result)
        assertEquals("the old session's segments are gone before any new one is fetched", emptyList<String>(), segmentFiles())
        assertEquals(FetchResult.Done, fetch(second, fresh!!, 0))
        assertOneSession(session(2), newShape.size)
    }

    @Test fun aReapedSessionIsRestartConversion() {
        hls.killAt = 2
        val f = hlsFetcher()
        val live = open(f, resolved(session(1))).first!!
        assertEquals(FetchResult.RestartConversion, fetch(f, live, 0))
        hls.killAt = null
        hls.segment = { MockResponse().setResponseCode(503) }
        assertEquals(FetchResult.RestartConversion, fetch(f, live, 2))
    }

    @Test fun aSegmentThatIsHtmlOrDoesNotStartWithTheSyncByteOrIsTooBigIsRefused() {
        val f = hlsFetcher(maxSegment = 1_000)
        val live = open(f, resolved(session(1))).first!!
        hls.segment = { MockResponse().setHeader("Content-Type", "text/html").setBody("<html>") }
        assertEquals(FetchResult.Failed(DownloadFailure.SERVER_ERROR), fetch(f, live, 0))
        hls.segment = { MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(ByteArray(188) { 0x46 })) }
        assertEquals(FetchResult.Failed(DownloadFailure.SERVER_ERROR), fetch(f, live, 0))
        hls.segment = { MockResponse().setBody(Buffer().write(ByteArray(1_001) { 0x47 })) }
        assertEquals("a declared length over the limit", FetchResult.Failed(DownloadFailure.SERVER_ERROR), fetch(f, live, 0))
        hls.segment = { MockResponse().setChunkedBody(Buffer().write(ByteArray(1_001) { 0x47 }), 100) }
        assertEquals("an undeclared one", FetchResult.Failed(DownloadFailure.SERVER_ERROR), fetch(f, live, 0))
        hls.segment = { MockResponse().setBody(Buffer().write(ByteArray(188) { 0x47 })).setHeader("Content-Encoding", "gzip") }
        assertEquals(FetchResult.Failed(DownloadFailure.SERVER_ERROR), fetch(f, live, 0))
        assertEquals("nothing was kept", emptyList<String>(), segmentFiles())
        assertTrue(hls.segmentRequests.isNotEmpty())
    }

    @Test fun aPlaylistNamingAnotherApiIsRefusedBeforeAnySegmentRequest() {
        hls.media = { "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-TARGETDURATION:3\n#EXTINF:2.0,\n/System/Info\n#EXT-X-ENDLIST\n" }
        val (live, result) = open(hlsFetcher(), resolved(session(1)))
        assertNull(live)
        assertEquals(FetchResult.Failed(DownloadFailure.SERVER_ERROR), result)
        hls.media = { "#EXTM3U\n#EXT-X-PLAYLIST-TYPE:VOD\n#EXT-X-TARGETDURATION:3\n#EXTINF:2.0,\n../../../../System/Info.ts\n#EXT-X-ENDLIST\n" }
        assertEquals(FetchResult.Failed(DownloadFailure.SERVER_ERROR), open(hlsFetcher(), resolved(session(1))).second)
        assertTrue(hls.segmentRequests.isEmpty())
        assertTrue(hls.all.none { it.path!!.contains("System") })
    }

    @Test fun aDurationSumOffByTwentyPercentIsServerError() {
        assertEquals(FetchResult.Failed(DownloadFailure.SERVER_ERROR), open(hlsFetcher(), resolved(session(1)), runtimeMs = 12_500).second)
        assertEquals("within 10% is fine", FetchResult.Done, open(hlsFetcher(), resolved(session(2)), runtimeMs = 10_900).second)
        assertEquals("an unknown runtime is not checked", FetchResult.Done, open(hlsFetcher(), resolved(session(3)), runtimeMs = null).second)
    }

    @Test fun a401OnASegmentOrAPlaylistIsUnauthorised() {
        val f = hlsFetcher()
        val live = open(f, resolved(session(1))).first!!
        hls.segment = { MockResponse().setResponseCode(401) }
        assertEquals(FetchResult.Unauthorised, fetch(f, live, 0))
        hls.media = null
        hls.shapes.clear()
        web.dispatcher = object : Dispatcher() { override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(401) }
        assertEquals(FetchResult.Unauthorised, open(f, resolved(session(2))).second)
    }

    @Test fun aPlanThatIsNotAServersHlsConversionIsRefused() {
        val r = resolved(session(1))
        val direct = Resolved(r.plan.copy(method = PlayMethod.DIRECT_PLAY, source = StreamSource("/Videos/$item/stream", emptyList(), hls = false)), r.route)
        assertEquals(FetchResult.Failed(DownloadFailure.SERVER_ERROR), open(hlsFetcher(), direct).second)
        assertEquals(0, hls.all.size)
    }
}
