package app.shoalwatch.data.subtitles

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.core.AppError
import app.shoalwatch.data.api.ServerApi
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.subtitles.api.SubtitlesGateway
import app.shoalwatch.data.inMemoryDb
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerClients
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.data.servers.Session
import app.shoalwatch.net.HttpClientFactory
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.security.SecretBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator

/** Remote subtitles: paths as sent, the language and id checks, sent once, refusals, bounded answers, the timed check (Milestone 11). */
@RunWith(RobolectricTestRunner::class)
class SubtitlesGatewayTest {
    private inner class Online(val online: Connection.Online) : ServerConnections {
        override fun connection(serverId: String): StateFlow<Connection> = MutableStateFlow(online)
        override suspend fun awaitOnline(serverId: String, timeoutMs: Long): Connection.Online = online
        override fun refresh() {}
        override fun refresh(serverId: String) {}
        override fun refreshIfCurrent(serverId: String, failed: Connection.Online) {}
    }

    private val web = MockWebServer()
    private val session = Session(S1, U1)
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val clients = ServerClients(HttpClientFactory(HandshakeCertificates.Builder().build().trustManager, { error("no client certificates here") }))
    private lateinit var db: AppDatabase
    private lateinit var store: ServerStore
    private lateinit var subs: SubtitlesGateway

    private val seen = mutableListOf<RecordedRequest>()
    private var route: (RecordedRequest) -> MockResponse = { MockResponse().setResponseCode(404) }

    @Before fun open() {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                synchronized(seen) { seen += request }
                return route(request)
            }
        }
        web.start()
        db = inMemoryDb()
        store = ServerStore(db, SecretBox { key }, clients::evict)
        val address = ServerAddress.parse("http://127.0.0.1:${web.port}")
        val api = ServerApi(store, Online(Connection.Online(address, isHome = true)), clients, DeviceIdentity("dev-1", "Pixel", "Shoalwatch", "0.1"))
        subs = SubtitlesGateway(api, phoneLanguage = { "fra" })
        runBlocking {
            store.upsertServer(Server(S1, "Den", "12.4.0", address, null, true, null, null, emptyMap()))
            store.upsertAccount(Account(S1, U1, "ben", "tok-1"), makeActive = true)
            store.warmTokens()
        }
    }

    @After fun close() { db.close(); web.shutdown() }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun id(n: Int) = "%032x".format(n)
    private fun url(r: RecordedRequest): HttpUrl = r.requestUrl!!
    private fun requests() = synchronized(seen) { seen.toList() }
    private fun sig() = requests().map { it.method + " " + url(it).encodedPath }
    private val movie = id(500)

    private fun refusal(block: suspend () -> Unit): AppError {
        try {
            runBlocking { block() }
        } catch (e: AppError) {
            return e
        }
        fail("expected a refusal")
        error("unreachable")
    }

    private fun answering(me: Int, other: (RecordedRequest) -> MockResponse) {
        route = { r -> if (r.path == "/Users/Me") (if (me == 200) json("""{"Id":"$U1"}""") else MockResponse().setResponseCode(me)) else other(r) }
    }

    private fun sub(id: String = "srt-eng-1", extra: String = "") = """{"Id":"$id","ProviderName":"OpenSubtitles","Name":"Film.2020.srt","Format":"srt"$extra}"""

    // ---- search

    @Test fun searchReadsTheLanguagePathAndMapsTextOnly() {
        route = {
            json(
                "[" + sub(
                    "srt-eng-1",
                    ""","CommunityRating":8.5,"DownloadCount":1200,"HearingImpaired":true,"Forced":false,"Author":"x",""" +
                        """"Comment":"http://evil.example/a","DateCreated":"2020-01-01T00:00:00Z"""",
                ) + "]",
            )
        }
        val got = runBlocking { subs.search(session, movie, "eng") } as SubtitleSearch.Found
        assertEquals(listOf("GET /Items/$movie/RemoteSearch/Subtitles/eng"), sig())
        val c = got.candidates.single()
        assertEquals("srt-eng-1", c.id)
        assertEquals("OpenSubtitles", c.provider)
        assertEquals("Film.2020.srt", c.name)
        assertEquals("srt", c.format)
        assertEquals(8.5f, c.rating)
        assertEquals(1200, c.downloads)
        assertTrue(c.hearingImpaired)
        assertFalse(c.forced)
        assertFalse(c.toString().contains("evil"))
    }

    @Test fun anyLanguageButThreeLowerCaseLettersIsRefusedWithNothingSent() {
        route = { json("[]") }
        for (bad in listOf("en", "ENG", "engl", "e/g", "../", "", "en1", "éng", "eng\n")) {
            assertEquals(bad, AppError.Kind.UNKNOWN, refusal { subs.search(session, movie, bad) }.kind)
        }
        assertEquals(0, requests().size)
    }

    @Test fun anItemIdThatIsNotAnIdIsRefusedWithNothingSent() {
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, refusal { subs.search(session, "../x", "eng") }.kind)
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, refusal { subs.download(session, "a/b", "srt-eng-1") }.kind)
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, refusal { subs.subtitleCount(session, "") }.kind)
        assertEquals(0, requests().size)
    }

    @Test fun hostileResultsAreBoundedAndBadEntriesDropped() {
        val many = (1..200).joinToString(",") { sub("srt-eng-$it") }
        val bad = listOf(
            sub("../x"), sub("a/b"), sub("a\\\\b"), sub(""), sub("a b"), sub("x".repeat(129)), sub("a%2Fb"), sub("q?x=1"),
            """{"Id":"ok-1"}""", "42", "null", """{"Id":7}""",
        ).joinToString(",")
        route = { json("[$bad,${sub("srt-eng-dup")},${sub("srt-eng-dup")},$many]") }
        val got = (runBlocking { subs.search(session, movie, "eng") } as SubtitleSearch.Found).candidates
        assertEquals(SubtitleLimits.RESULTS, got.size)
        assertEquals("srt-eng-dup", got[0].id)
        assertEquals(got.size, got.map { it.id }.toSet().size)
        assertTrue(got.all { isSubtitleId(it.id) })
    }

    @Test fun longTextAndOddNumbersAreCleaned() {
        val long = "n".repeat(5000)
        route = {
            json(
                """[{"Id":"a-1","ProviderName":"P\u0000‮","Name":"$long","Format":"averyveryverylongformat","CommunityRating":1e300,"DownloadCount":-5},""" +
                    """{"Id":"a-2","Name":"B","CommunityRating":-1,"DownloadCount":99999999999}]""",
            )
        }
        val got = (runBlocking { subs.search(session, movie, "eng") } as SubtitleSearch.Found).candidates
        assertEquals(SubtitleLimits.TEXT, got[0].name.length)
        assertEquals("P", got[0].provider)
        assertEquals(SubtitleLimits.FORMAT, got[0].format!!.length)
        assertEquals(10f, got[0].rating)
        assertNull(got[0].downloads)
        assertNull(got[1].rating)
        assertEquals(Int.MAX_VALUE, got[1].downloads)
    }

    @Test fun anOversizedSearchAnswerIsAServerError() {
        route = { json("[{\"Id\":\"a-1\",\"Name\":\"" + "n".repeat(3 * 1024 * 1024) + "\"}]") }
        assertEquals(AppError.Kind.SERVER_ERROR, refusal { subs.search(session, movie, "eng") }.kind)
    }

    // ---- id check

    @Test fun theSubtitleIdCharsetFitsRealIdsAndRefusesAnythingThatCouldMoveThePath() {
        for (ok in listOf("srt-eng-1234567", "ass_fra.2", "A1", "x".repeat(128), "a.b")) assertTrue(ok, isSubtitleId(ok))
        for (bad in listOf("", "..", "a..b", "../a", "a/b", "a\\b", "a%2f", "a?b", "a#b", "a b", "a\u0000b", "a\nb", "a\tb", "é", "x".repeat(129))) {
            assertFalse(bad, isSubtitleId(bad))
        }
        assertFalse(isSubtitleId(null))
    }

    @Test fun downloadWithABadSubtitleIdSendsNothing() {
        route = { MockResponse().setResponseCode(204) }
        for (bad in listOf("..", "a/../b", "a/b", "a\\b", "a\u0000", "a\u0007b", "", "x".repeat(200))) {
            assertEquals(bad, AppError.Kind.UNKNOWN, refusal { subs.download(session, movie, bad) }.kind)
        }
        assertEquals(0, requests().size)
    }

    // ---- download

    @Test fun downloadIsOnePostWithNoBodyAndNoQuery() {
        route = { MockResponse().setResponseCode(204) }
        assertEquals(SubtitleDownload.Requested, runBlocking { subs.download(session, movie, "srt-eng-1") })
        val sent = requests().single()
        assertEquals("POST", sent.method)
        assertEquals("/Items/$movie/RemoteSearch/Subtitles/srt-eng-1", url(sent).encodedPath)
        assertNull(url(sent).encodedQuery)
        assertEquals(0L, sent.bodySize)
    }

    @Test fun aFailedDownloadIsSentOnceAndNeverRetried() {
        for (code in listOf(500, 503)) {
            synchronized(seen) { seen.clear() }
            route = { MockResponse().setResponseCode(code) }
            assertEquals(AppError.Kind.SERVER_ERROR, refusal { subs.download(session, movie, "srt-eng-1") }.kind)
            assertEquals(code.toString(), 1, requests().size)
        }
    }

    // ---- refusals

    @Test fun a403IsNotAllowedForSearchAndDownloadAndTheSignInKept() {
        route = { MockResponse().setResponseCode(403) }
        assertEquals(SubtitleSearch.NotAllowed, runBlocking { subs.search(session, movie, "eng") })
        assertEquals(SubtitleDownload.NotAllowed, runBlocking { subs.download(session, movie, "srt-eng-1") })
        assertEquals(2, requests().size)
        assertEquals("tok-1", store.cachedToken(S1, U1))
    }

    @Test fun a401CheckedWithUsersMeIsNotAllowedAndTheSignInKept() {
        answering(me = 200) { MockResponse().setResponseCode(401) }
        assertEquals(SubtitleSearch.NotAllowed, runBlocking { subs.search(session, movie, "eng") })
        assertEquals(listOf("GET /Items/$movie/RemoteSearch/Subtitles/eng", "GET /Users/Me"), sig())
        assertEquals("tok-1", store.cachedToken(S1, U1))

        synchronized(seen) { seen.clear() }
        assertEquals(SubtitleDownload.NotAllowed, runBlocking { subs.download(session, movie, "srt-eng-1") })
        assertEquals(listOf("POST /Items/$movie/RemoteSearch/Subtitles/srt-eng-1", "GET /Users/Me"), sig())
        assertEquals("tok-1", store.cachedToken(S1, U1))
    }

    @Test fun a401ThatIsTheSignInEndingSignsOut() {
        answering(me = 401) { MockResponse().setResponseCode(401) }
        assertEquals(AppError.Kind.UNAUTHORISED, refusal { subs.search(session, movie, "eng") }.kind)
        assertEquals(null, store.cachedToken(S1, U1))
    }

    // ---- count

    @Test fun subtitleCountCountsOnlySubtitleStreams() {
        route = {
            json("""{"Id":"$movie","MediaStreams":[{"Type":"Video"},{"Type":"Audio"},{"Type":"Subtitle"},{"Type":"Subtitle","IsExternal":true},{"Type":"EmbeddedImage"},7,null,{"Type":5}]}""")
        }
        assertEquals(2, runBlocking { subs.subtitleCount(session, movie) })
        val u = url(requests().single())
        assertEquals("/Items/$movie", u.encodedPath)
        assertEquals(U1, u.queryParameter("userId"))
    }

    @Test fun subtitleCountScansAtMostAThousandStreams() {
        val streams = (1..3000).joinToString(",") { """{"Type":"Subtitle"}""" }
        route = { json("""{"MediaStreams":[$streams]}""") }
        assertEquals(1000, runBlocking { subs.subtitleCount(session, movie) })
    }

    @Test fun noStreamsIsZero() {
        route = { json("""{"Id":"$movie"}""") }
        assertEquals(0, runBlocking { subs.subtitleCount(session, movie) })
    }

    // ---- the timed check (virtual time)

    private fun countingRoute(counts: List<Int>): AtomicInteger {
        val reads = AtomicInteger()
        route = {
            val n = counts[minOf(reads.getAndIncrement(), counts.lastIndex)]
            json("""{"MediaStreams":[${(1..n).joinToString(",") { """{"Type":"Subtitle"}""" }}]}""")
        }
        return reads
    }

    @Test fun arrivalIsReportedAtFourSecondsWhenTheCountGrowsThen() = runTest {
        val reads = countingRoute(listOf(1, 2))
        assertEquals(Arrival.Arrived, subs.awaitArrival(session, movie, 1))
        assertEquals(4000L, currentTime)
        assertEquals(2, reads.get())
    }

    @Test fun arrivalAtTwoSecondsStopsReadingAtOnce() = runTest {
        val reads = countingRoute(listOf(2))
        assertEquals(Arrival.Arrived, subs.awaitArrival(session, movie, 1))
        assertEquals(2000L, currentTime)
        assertEquals(1, reads.get())
    }

    @Test fun neverArrivingIsNotYetAfterSixteenSecondsAndFourReads() = runTest {
        val reads = countingRoute(listOf(1))
        assertEquals(Arrival.NotYet, subs.awaitArrival(session, movie, 1))
        assertEquals(16000L, currentTime)
        assertEquals(4, reads.get())
    }

    @Test fun readsAreAtTwoFourEightAndSixteenSecondsCumulative() = runTest {
        val stamps = mutableListOf<Long>()
        val address = ServerAddress.parse("http://127.0.0.1:${web.port}")
        val api = ServerApi(store, Online(Connection.Online(address, isHome = true)), clients, DeviceIdentity("dev-1", "Pixel", "Shoalwatch", "0.1"))
        val timed = SubtitlesGateway(api, pause = { ms -> stamps += ms; delay(ms) })
        route = { json("""{"MediaStreams":[]}""") }
        assertEquals(Arrival.NotYet, timed.awaitArrival(session, movie, 0))
        assertEquals(listOf(2000L, 2000L, 4000L, 8000L), stamps)
    }

    @Test fun aFailedReadCountsAsNotYetAndTheNextOneStillCounts() = runTest {
        val reads = AtomicInteger()
        route = { if (reads.getAndIncrement() == 0) MockResponse().setResponseCode(404) else json("""{"MediaStreams":[{"Type":"Subtitle"}]}""") }
        assertEquals(Arrival.Arrived, subs.awaitArrival(session, movie, 0))
        assertEquals(4000L, currentTime)
    }

    @Test fun theWaitCanBeCancelled() = runTest {
        route = { json("""{"MediaStreams":[]}""") }
        val job = launch { subs.awaitArrival(session, movie, 0) }
        testScheduler.advanceTimeBy(1000)
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(0, requests().size)
    }

    // ---- language

    @Test fun theProfilesLanguageWinsWhenValid() {
        route = { json("""{"Id":"$U1","Configuration":{"SubtitleLanguagePreference":"spa"}}""") }
        assertEquals("spa", runBlocking { subs.preferredLanguage(session) })
        assertEquals(listOf("GET /Users/Me"), sig())
    }

    @Test fun anInvalidProfileLanguageFallsBackToThePhone() {
        for (body in listOf("""{"Configuration":{"SubtitleLanguagePreference":"es"}}""", """{"Configuration":{"SubtitleLanguagePreference":"../x"}}""", """{"Configuration":{}}""", "{}")) {
            route = { json(body) }
            assertEquals(body, "fra", runBlocking { subs.preferredLanguage(session) })
        }
    }

    @Test fun aFailedUsersMeFallsBackToThePhone() {
        route = { MockResponse().setResponseCode(500) }
        assertEquals("fra", runBlocking { subs.preferredLanguage(session) })
    }

    @Test fun theFallbackChainIsPure() {
        assertEquals("deu", chooseLanguage("deu", "fra"))
        assertEquals("deu", chooseLanguage(" DEU ", "fra"))
        assertEquals("fra", chooseLanguage(null, "fra"))
        assertEquals("fra", chooseLanguage("", "fra"))
        assertEquals("fra", chooseLanguage("zz", "fra"))
        assertEquals("eng", chooseLanguage(null, null))
        assertEquals("eng", chooseLanguage("english", "fr"))
        assertEquals("eng", chooseLanguage("a/b", "x1y"))
    }

    @Test fun thePhonesLanguageIsIso639Dash2() {
        assertEquals("fra", phoneLanguage(Locale.FRANCE))
        assertEquals("eng", phoneLanguage(Locale.UK))
        assertNull(phoneLanguage(Locale.ROOT))
    }
}
