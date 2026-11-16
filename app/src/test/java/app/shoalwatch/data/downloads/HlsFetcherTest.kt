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
        fake.free[null] = 1L shl 40
    }

    private fun session(n: Int) = "%032x".format(n)

    private fun resolved(sid: String, shape: List<Double> = List(5) { 2.0 }, quality: Quality = Quality.MBPS_8): Resolved {
        hls.shapes[sid] = shape
        val plan = PlaybackPlan(
            request = PlaybackRequest(item, quality), mediaSourceId = "src1", playSessionId = sid, method = PlayMethod.TRANSCODE,
            source = StreamSource("/videos/$item/master.m3u8", listOf("PlaySessionId" to sid), hls = true),
            audioTracks = emptyList(), subtitleTracks = emptyList(), audioIndex = null, runtimeMs = 10_000, videoRange = VideoRange.SDR,
            transcodeReasons = emptySet(), hasSegments = false, eTag = null,
        )
        return Resolved(plan, runBlocking { api.mediaRoute(session) })
    }

    private fun hlsFetcher(maxSegment: Long = 64L * 1024 * 1024, stall: StallRule = StallRule(), playlistMs: Long = 60_000) =
        HlsFetcher(Dispatchers.IO, clock, paths, fake.volumes, maxSegment, stall, playlistMs)

    private fun open(f: HlsFetcher, r: Resolved, runtimeMs: Long? = 10_000, space: ConversionSpace = ConversionSpace(StorageKind.INTERNAL, null, null)) =
        runBlocking { f.open(r, folder, runtimeMs, space) }

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

    // Tasks 5-8 review I-3: a conversion has a total budget, a per-segment cap from its bitrate, a free-space check before every
    // segment that keeps the reserve, and a reservation on internal storage. 1.5 Mbps over 5 x 2 s: 2 x 187,500 B/s x 10 s plus
    // 8 MiB of allowance = 12,138,608 bytes; the per-segment cap is max(4 MiB, 2 x bitrate x duration) = 4 MiB.

    private fun bytes(n: Int) = MockResponse().setHeader("Content-Type", "video/mp2t").setBody(Buffer().write(ByteArray(n) { 0x47 }))

    @Test fun aConversionThatOverrunsItsBudgetFailsBeforeTheDiskFills() {
        val f = hlsFetcher()
        val live = open(f, resolved(session(1), quality = Quality.MBPS_1_5)).first!!
        hls.segment = { bytes(3 shl 20) }
        assertEquals(FetchResult.Failed(DownloadFailure.SERVER_ERROR), fetch(f, live, 0))
        assertEquals("three of 3 MiB fit; the fourth would pass the budget", 3, segmentFiles().size)
    }

    @Test fun theBudgetIsNeverAboveThePlannedSizeTimesThree() {
        val f = hlsFetcher()
        val live = open(f, resolved(session(1), quality = Quality.MBPS_1_5), space = ConversionSpace(StorageKind.INTERNAL, null, 100_000)).first!!
        hls.segment = { bytes(3 shl 20) }
        assertEquals(FetchResult.Failed(DownloadFailure.SERVER_ERROR), fetch(f, live, 0))
        assertEquals("300,000 + 8 MiB: two fit", 2, segmentFiles().size)
    }

    @Test fun theBudgetCountsSegmentsAlreadySavedWhenResuming() {
        val f = hlsFetcher()
        val live = open(f, resolved(session(1), quality = Quality.MBPS_1_5)).first!!
        hls.segment = { n -> if (n < 2) bytes(3 shl 20) else null }
        hls.dropAt = 2
        assertEquals(FetchResult.Retry(AppError.Kind.NETWORK), fetch(f, live, 0))
        hls.segment = { bytes(3 shl 20) }
        assertEquals(FetchResult.Failed(DownloadFailure.SERVER_ERROR), fetch(f, live, 2))
        assertEquals(3, segmentFiles().size)
    }

    @Test fun aSegmentOverItsBitratesCapIsRefused() {
        val f = hlsFetcher()
        val live = open(f, resolved(session(1), List(5) { 6.0 }, Quality.MBPS_1_5), runtimeMs = null).first!!
        hls.segment = { bytes(5 shl 20) } // within the budget (30 s), over the 4 MiB cap; the old flat cap was 64 MiB
        assertEquals(FetchResult.Failed(DownloadFailure.SERVER_ERROR), fetch(f, live, 0))
        assertEquals(emptyList<String>(), segmentFiles())
    }

    @Test fun tooLittleFreeSpaceAboveTheReserveIsNoSpaceBeforeTheSegmentIsAskedFor() {
        val f = hlsFetcher()
        val live = open(f, resolved(session(1))).first!!
        fake.free[null] = (1L shl 30) + (1L shl 20) // the 1 GiB internal reserve plus less than one segment's cap
        assertEquals(FetchResult.Failed(DownloadFailure.NO_SPACE), fetch(f, live, 0))
        assertTrue(hls.segmentRequests.isEmpty())
    }

    @Test fun aCardKeepsItsOwnReserve() {
        val card = tmp.newFolder("card")
        fake.card("CARD-1", card)
        fake.free["CARD-1"] = (256L shl 20) + (1L shl 20)
        val f = hlsFetcher()
        val live = open(f, resolved(session(1)), space = ConversionSpace(StorageKind.REMOVABLE, "CARD-1", null)).first!!
        assertEquals(FetchResult.Failed(DownloadFailure.NO_SPACE), fetch(f, live, 0))
        assertEquals("no reservation on a card", emptyList<Pair<File, Long>>(), fake.reserved)
    }

    @Test fun openingOnInternalStorageReservesTheBudget() {
        open(hlsFetcher(), resolved(session(1), quality = Quality.MBPS_1_5))
        assertEquals(1, fake.reserved.size)
        assertEquals(File(hlsDir, "local.m3u8"), fake.reserved.single().first)
        assertEquals(3_750_000L + (8L shl 20), fake.reserved.single().second)
    }

    @Test fun aPlaylistOrASegmentThatTricklesIsCut() {
        hls.media = { sid -> hls.playlist(hls.shapes.getValue(sid)) }
        val slowPlaylists = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = hls.dispatch(request).throttleBody(16, 200, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        web.dispatcher = slowPlaylists
        val result = open(hlsFetcher(playlistMs = 500), resolved(session(1))).second
        assertTrue("a playlist past its deadline is a retry: $result", result is FetchResult.Retry)
        web.dispatcher = hls
        val f = hlsFetcher(stall = StallRule(windowMs = 500, minBytes = 1_024))
        val live = open(f, resolved(session(2))).first!!
        hls.segment = { bytes(64 * 1024).throttleBody(64, 200, java.util.concurrent.TimeUnit.MILLISECONDS) }
        assertEquals(FetchResult.Retry(AppError.Kind.TIMEOUT), fetch(f, live, 0))
    }
}
