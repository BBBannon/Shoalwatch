package app.shoalwatch.data.discover

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.core.AppError
import app.shoalwatch.data.api.ServerApi
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.discover.api.DiscoverGateway
import app.shoalwatch.data.discover.api.browseQuery
import app.shoalwatch.data.discover.api.onlineTrailerOf
import app.shoalwatch.data.images.ArtworkType
import app.shoalwatch.data.inMemoryDb
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.ListShape
import app.shoalwatch.data.library.Sort
import app.shoalwatch.data.library.SortBy
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
import kotlinx.coroutines.runBlocking
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
import javax.crypto.KeyGenerator

/** Discovery: every path and query as sent, answers as mapped and bounded, hostile answers, parts that fail alone (Milestone 11). */
@RunWith(RobolectricTestRunner::class)
class DiscoverGatewayTest {
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
    private lateinit var discover: DiscoverGateway

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
        discover = DiscoverGateway(api)
        runBlocking {
            store.upsertServer(Server(S1, "Den", "12.4.0", address, null, true, null, null, emptyMap()))
            store.upsertAccount(Account(S1, U1, "ben", "tok-1"), makeActive = true)
            store.warmTokens()
        }
    }

    @After fun close() { db.close(); web.shutdown() }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun id(n: Int) = "%032x".format(n)
    private fun item(n: Int, type: String = "Movie", extra: String = "") = """{"Id":"${id(n)}","Name":"Item $n","Type":"$type"$extra}"""
    private fun page(vararg items: String, total: Int? = null) = """{"Items":[${items.joinToString(",")}],"TotalRecordCount":${total ?: items.size}}"""
    private fun url(r: RecordedRequest): HttpUrl = r.requestUrl!!
    private fun requests() = synchronized(seen) { seen.toList() }
    private fun paths() = requests().map { url(it).encodedPath }

    private fun refusal(block: suspend () -> Unit): AppError {
        try {
            runBlocking { block() }
        } catch (e: AppError) {
            return e
        }
        fail("expected a refusal")
        error("unreachable")
    }

    /** Answers by path; anything not listed is a 404 (a part that fails). */
    private fun routes(vararg answers: Pair<String, String>) {
        val map = answers.toMap()
        route = { r -> map[url(r).encodedPath]?.let { json(it) } ?: MockResponse().setResponseCode(404) }
    }

    private val self = id(500)

    // ---- extras

    @Test fun extrasReadFourPartsWithTheExactPathsAndQueries() {
        val person = """{"Id":"${id(70)}","Name":"Ada","Role":"Hero","Type":"Actor","PrimaryImageTag":"abc123"}"""
        routes(
            "/Items/$self" to """{"Id":"$self","People":[$person],"RemoteTrailers":[{"Url":"https://www.youtube.com/watch?v=x","Name":"Trailer"}],""" +
                """"MediaSources":[{"Id":"${id(601)}","Name":"1080p"},{"Id":"${id(602)}","Name":"4K"}]}""",
            "/Items/$self/LocalTrailers" to "[${item(10, "Trailer")}]",
            "/Items/$self/SpecialFeatures" to "[${item(11, "Video")}]",
            "/Items/$self/Similar" to page(item(12), item(13)),
        )
        val got = runBlocking { discover.extras(session, self) }
        assertEquals(listOf(id(70)), got.cast.map { it.id })
        assertEquals("Hero", got.cast[0].role)
        assertEquals(ArtworkType.PRIMARY, got.cast[0].artwork!!.type)
        assertEquals("abc123", got.cast[0].artwork!!.tag)
        assertEquals(id(70), got.cast[0].artwork!!.itemId)
        assertEquals(listOf(id(10)), got.localTrailers.map { it.item.id })
        assertEquals(listOf(id(11)), got.specialFeatures.map { it.item.id })
        assertEquals(listOf(id(12), id(13)), got.similar.map { it.item.id })
        assertEquals("www.youtube.com", got.onlineTrailers.single().host)
        assertEquals(listOf(MediaVersion(id(601), "1080p"), MediaVersion(id(602), "4K")), got.versions)
        val byPath = requests().associateBy { url(it).encodedPath }
        assertEquals(4, byPath.size)
        byPath.values.forEach { assertEquals("GET", it.method); assertEquals(U1, url(it).queryParameter("userId")) }
        assertEquals("12", url(byPath.getValue("/Items/$self/Similar")).queryParameter("limit"))
    }

    @Test fun oneVersionIsNoChoice() {
        routes("/Items/$self" to """{"Id":"$self","MediaSources":[{"Id":"${id(601)}","Name":"Only"}]}""")
        assertTrue(runBlocking { discover.extras(session, self) }.versions.isEmpty())
    }

    /** Final review M1: the player honours only a 32-hex source id, so no other id is offered as a choice it would drop. */
    @Test fun onlyVersionsThePlayerCanHonourAreOffered() {
        val dashed = "6d4e7a3c-1f2b-4c5d-8e9f-0a1b2c3d4e5f"
        routes(
            "/Items/$self" to """{"Id":"$self","MediaSources":[{"Id":"$dashed","Name":"Dashed"},{"Id":"s1","Name":"Short"},""" +
                """{"Id":"${id(601)}","Name":"1080p"},{"Id":"${"g".repeat(32)}","Name":"Not hex"}]}""",
        )
        assertTrue("one usable version is no choice", runBlocking { discover.extras(session, self) }.versions.isEmpty())
        routes(
            "/Items/$self" to """{"Id":"$self","MediaSources":[{"Id":"$dashed","Name":"Dashed"},{"Id":"${id(601)}","Name":"1080p"},""" +
                """{"Id":"${id(602)}","Name":"4K"}]}""",
        )
        assertEquals(listOf(id(601), id(602)), runBlocking { discover.extras(session, self) }.versions.map { it.id })
    }

    @Test fun eachPartFailsAloneAndTheOthersSurvive() {
        routes(
            "/Items/$self" to """{"Id":"$self","People":[{"Id":"${id(70)}","Name":"Ada"}]}""",
            "/Items/$self/Similar" to page(item(12)),
        )
        val got = runBlocking { discover.extras(session, self) }
        assertEquals(1, got.cast.size)
        assertEquals(1, got.similar.size)
        assertTrue(got.localTrailers.isEmpty())
        assertTrue(got.specialFeatures.isEmpty())
    }

    @Test fun theItemFailingBlanksOnlyCastTrailersAndVersions() {
        routes("/Items/$self/Similar" to page(item(12)), "/Items/$self/LocalTrailers" to "[${item(10)}]")
        val got = runBlocking { discover.extras(session, self) }
        assertTrue(got.cast.isEmpty() && got.onlineTrailers.isEmpty() && got.versions.isEmpty())
        assertEquals(1, got.similar.size)
        assertEquals(1, got.localTrailers.size)
    }

    @Test fun whenEveryPartFailsTheFailureIsThrown() {
        route = { MockResponse().setResponseCode(500) }
        refusal { discover.extras(session, self) }
    }

    @Test fun anAnswerAboutAnotherItemIsNotTrustedForCastOrVersions() {
        routes(
            "/Items/$self" to """{"Id":"${id(9)}","People":[{"Id":"${id(70)}","Name":"Ada"}]}""",
            "/Items/$self/Similar" to page(item(12)),
        )
        assertTrue(runBlocking { discover.extras(session, self) }.cast.isEmpty())
    }

    @Test fun hostileExtrasAreCutAndCleaned() {
        val people = (1..80).map { """{"Id":"${id(1000 + it)}","Name":"P$it","PrimaryImageTag":"t$it"}""" } +
            """{"Id":"nope","Name":"bad id"}""" + """{"Id":"${id(3)}"}""" + """{"Id":"${id(4)}","Name":"Z","PrimaryImageTag":"../x"}""" + "17"
        val long = "x".repeat(5000)
        val sources = (1..30).map { """{"Id":"${id(600 + it)}","Name":"v$it"}""" } + """{"Id":"a/b","Name":"slash"}"""
        routes(
            "/Items/$self" to """{"Id":"$self","People":[${people.joinToString(",")}],"RemoteTrailers":[{"Url":"https://$long.example.com/"}],""" +
                """"MediaSources":[${sources.joinToString(",")}]}""",
            "/Items/$self/LocalTrailers" to "[${(1..30).joinToString(",") { item(100 + it) }}]",
            "/Items/$self/SpecialFeatures" to "[${(1..80).joinToString(",") { item(200 + it) }}]",
            "/Items/$self/Similar" to page(*(1..40).map { item(300 + it) }.toTypedArray()),
        )
        val got = runBlocking { discover.extras(session, self) }
        assertEquals(50, got.cast.size)
        assertEquals(10, got.localTrailers.size)
        assertEquals(50, got.specialFeatures.size)
        assertEquals(12, got.similar.size)
        assertEquals(20, got.versions.size)
        assertFalse(got.versions.any { it.id.contains('/') })
        assertTrue(got.onlineTrailers.isEmpty())
    }

    @Test fun aPersonWithABadPictureTagStillShowsWithoutOne() {
        val dto = """{"Id":"${id(4)}","Name":"Z","PrimaryImageTag":"../x"}"""
        routes("/Items/$self" to """{"Id":"$self","People":[$dto,{"Id":"${id(5)}","Name":"‮Evil\u0007"}]}""")
        val cast = runBlocking { discover.extras(session, self) }.cast
        assertEquals(2, cast.size)
        assertNull(cast[0].artwork)
        assertEquals("Evil", cast[1].name)
    }

    @Test fun badItemIdsAreRefusedWithNothingSent() {
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, refusal { discover.extras(session, "../etc") }.kind)
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, refusal { discover.person(session, "A".repeat(32)) }.kind)
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, refusal { discover.personWorks(session, "x", 0) }.kind)
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, refusal { discover.browse(session, "x", ListShape.DIRECT, BrowseFilter(), Sort(SortBy.NAME, false), 0) }.kind)
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, refusal { discover.genres(session, "x", 0) }.kind)
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, refusal { discover.filterOptions(session, "x") }.kind)
        assertTrue(requests().isEmpty())
    }

    // ---- trailers (pure)

    @Test fun onlyPrintableHttpAddressesWithAHostAreKept() {
        assertEquals("youtube.com", onlineTrailerOf("http://YouTube.com/watch?v=1", "T")!!.host)
        assertEquals("v.example.org", onlineTrailerOf("https://v.example.org:8443/a", null)!!.host)
        listOf(
            "javascript:alert(1)", "intent://x#Intent;end", "file:///sdcard/a", "content://x/y", "ftp://x.com/a", "http:///nohost", "https://",
            "http://a b.com/", "http://x.com/\u0007", "http://x.com/‮", "https://user:pw@evil.com/", "http://[::1]/", "//x.com/a", "",
            "http://x.com/" + "a".repeat(2048), "https://exämple.com/",
        ).forEach { assertNull(it, onlineTrailerOf(it, null)) }
        assertNotNull(onlineTrailerOf("https://x.com/" + "a".repeat(2048 - 14), null))
    }

    @Test fun trailersOnTheWireAreFilteredBeforeTheyReachTheScreen() {
        routes(
            "/Items/$self" to """{"Id":"$self","RemoteTrailers":[{"Url":"javascript:1"},{"Url":"https://youtu.be/a"},{"Url":"https://youtu.be/a"},{"Url":7},{"Name":"none"}]}""",
        )
        assertEquals(listOf("https://youtu.be/a"), runBlocking { discover.extras(session, self) }.onlineTrailers.map { it.url })
    }

    // ---- person

    @Test fun aPersonIsReadByItsOwnId() {
        routes("/Items/${id(70)}" to """{"Id":"${id(70)}","Name":"Ada","Type":"Person","Overview":"Wrote it.","ImageTags":{"Primary":"p1"}}""")
        val p = runBlocking { discover.person(session, id(70)) }
        assertEquals("Ada", p.name)
        assertEquals("p1", p.images.primaryTag)
        val u = url(requests().single())
        assertEquals(U1, u.queryParameter("userId"))
    }

    @Test fun aPersonAnswerForAnotherIdIsAnError() {
        routes("/Items/${id(70)}" to item(71))
        assertEquals(AppError.Kind.SERVER_ERROR, refusal { discover.person(session, id(70)) }.kind)
    }

    @Test fun personWorksAreFilmsAndSeriesPaged() {
        routes("/Items" to page(item(1), item(2, "Series"), """{"Id":"bad"}""", total = 250))
        val got = runBlocking { discover.personWorks(session, id(70), 100) }
        assertEquals(listOf(id(1), id(2)), got.items.map { it.item.id })
        assertEquals(103, got.nextStartIndex)
        assertFalse(got.endReached)
        val u = url(requests().single())
        assertEquals(id(70), u.queryParameter("personIds"))
        assertEquals("true", u.queryParameter("recursive"))
        assertEquals("Movie,Series", u.queryParameter("includeItemTypes"))
        assertEquals("100", u.queryParameter("startIndex"))
        assertEquals("100", u.queryParameter("limit"))
        assertEquals(U1, u.queryParameter("userId"))
    }

    @Test fun aStartIndexOutOfRangeIsRefusedWithNothingSent() {
        assertEquals(AppError.Kind.UNKNOWN, refusal { discover.personWorks(session, id(70), -1) }.kind)
        assertEquals(AppError.Kind.UNKNOWN, refusal { discover.genres(session, null, 100_001) }.kind)
        assertTrue(requests().isEmpty())
    }

    // ---- browse

    @Test fun browseSendsTheFilterAndTheShape() {
        routes("/Items" to page(item(1)))
        val filter = BrowseFilter(genreId = id(5), studioId = id(6), year = 1999, rating = "PG-13", tag = "cosy", letter = "B")
        val got = runBlocking { discover.browse(session, id(9), ListShape.forLibrary(app.shoalwatch.data.library.LibraryKind.MOVIES), filter, Sort(SortBy.RATING, true), 0) }
        assertEquals(1, got.items.size)
        assertTrue(got.endReached)
        val u = url(requests().single())
        assertEquals("/Items", u.encodedPath)
        assertEquals(id(9), u.queryParameter("parentId"))
        assertEquals(id(5), u.queryParameter("genreIds"))
        assertEquals(id(6), u.queryParameter("studioIds"))
        assertEquals("1999", u.queryParameter("years"))
        assertEquals("PG-13", u.queryParameter("officialRatings"))
        assertEquals("cosy", u.queryParameter("tags"))
        assertEquals("B", u.queryParameter("nameStartsWith"))
        assertNull(u.queryParameter("nameLessThan"))
        assertEquals("Movie", u.queryParameter("includeItemTypes"))
        assertEquals("true", u.queryParameter("recursive"))
        assertEquals("CommunityRating,SortName", u.queryParameter("sortBy"))
        assertEquals("Descending,Ascending", u.queryParameter("sortOrder"))
        assertEquals("true", u.queryParameter("enableImages"))
    }

    // ---- filter options, genres, studios

    @Test fun filterOptionsReadThreeListsForTheLibrary() {
        routes(
            "/Items/Filters" to """{"Years":[1999,2005,"x",3000,2005],"OfficialRatings":["PG","a,b","15"],"Tags":["cosy","x|y"]}""",
            "/Genres" to page(item(21, "Genre"), """{"Id":"bad","Name":"x"}"""),
            "/Studios" to page(item(31, "Studio")),
        )
        val got = runBlocking { discover.filterOptions(session, id(9)) }
        assertEquals(listOf(2005, 1999), got.years)
        assertEquals(listOf("PG", "15"), got.ratings)
        assertEquals(listOf("cosy"), got.tags)
        assertEquals(listOf(Named(id(21), "Item 21")), got.genres)
        assertEquals(listOf(Named(id(31), "Item 31")), got.studios)
        requests().forEach {
            assertEquals(id(9), url(it).queryParameter("parentId"))
            assertEquals(U1, url(it).queryParameter("userId"))
        }
        assertEquals(setOf("/Items/Filters", "/Genres", "/Studios"), paths().toSet())
    }

    @Test fun filterOptionsFailAloneAndAreBounded() {
        routes("/Genres" to page(*(1..300).map { item(it, "Genre") }.toTypedArray()))
        val got = runBlocking { discover.filterOptions(session, id(9)) }
        assertTrue(got.years.isEmpty() && got.studios.isEmpty())
        assertEquals(200, got.genres.size)
    }

    @Test fun genresAndStudiosArePagedWithAnOptionalLibrary() {
        routes("/Genres" to page(item(21, "Genre"), total = 300), "/Studios" to page(item(31, "Studio")))
        val g = runBlocking { discover.genres(session, id(9), 100) }
        assertEquals(1, g.items.size)
        assertFalse(g.endReached)
        runBlocking { discover.studios(session, null, 0) }
        val (gr, sr) = requests()
        assertEquals(id(9), url(gr).queryParameter("parentId"))
        assertEquals("100", url(gr).queryParameter("startIndex"))
        assertEquals("100", url(gr).queryParameter("limit"))
        assertNull(url(sr).queryParameter("parentId"))
        assertEquals("/Studios", url(sr).encodedPath)
    }

    // ---- search extras

    @Test fun searchMoreAsksThreeEndpointsWithTenEach() {
        routes(
            "/Persons" to page(item(40, "Person", ""","ImageTags":{"Primary":"pp"}""")),
            "/Genres" to page(item(41, "Genre")),
            "/Items" to page(item(42, "BoxSet")),
        )
        val got = runBlocking { discover.searchMore(session, "  star  ") }
        assertEquals(id(40), got.people.single().id)
        assertEquals("pp", got.people.single().artwork!!.tag)
        assertEquals(id(41), got.genres.single().id)
        assertEquals(id(42), got.collections.single().item.id)
        assertEquals(setOf("/Persons", "/Genres", "/Items"), paths().toSet())
        requests().forEach {
            assertEquals("star", url(it).queryParameter("searchTerm"))
            assertEquals("10", url(it).queryParameter("limit"))
        }
        assertEquals("BoxSet", url(requests().first { url(it).encodedPath == "/Items" }).queryParameter("includeItemTypes"))
    }

    @Test fun searchMoreEachFailsAloneAndABlankTermSendsNothing() {
        routes("/Genres" to page(item(41, "Genre")))
        val got = runBlocking { discover.searchMore(session, "x") }
        assertEquals(1, got.genres.size)
        assertTrue(got.people.isEmpty() && got.collections.isEmpty())
        seen.clear()
        assertEquals(SearchExtras.NONE, runBlocking { discover.searchMore(session, " \u0007 ") })
        assertTrue(requests().isEmpty())
    }

    @Test fun searchMoreCutsLongAnswersAtTen() {
        routes("/Persons" to page(*(1..30).map { item(it, "Person") }.toTypedArray()), "/Genres" to page(*(1..30).map { item(100 + it, "Genre") }.toTypedArray()), "/Items" to page(*(1..30).map { item(200 + it, "BoxSet") }.toTypedArray()))
        val got = runBlocking { discover.searchMore(session, "x") }
        assertEquals(listOf(10, 10, 10), listOf(got.people.size, got.genres.size, got.collections.size))
    }

    @Test fun browsingWithNoLibraryAsksAcrossAllOfThemWithNoParentId() {
        routes("/Items" to page(item(1)))
        runBlocking { discover.browse(session, null, ListShape(setOf(ItemKind.MOVIE, ItemKind.SERIES), recursive = true), BrowseFilter(genreId = id(5)), Sort(SortBy.NAME, false), 0) }
        val u = url(requests().single())
        assertNull(u.queryParameter("parentId"))
        assertEquals(id(5), u.queryParameter("genreIds"))
        assertEquals("true", u.queryParameter("recursive"))
        assertNull(browseQuery(U1, null, ListShape.DIRECT, BrowseFilter(), Sort(SortBy.NAME, false), 0).toMap()["parentId"])
    }

    // ---- the pure query

    private val byName = Sort(SortBy.NAME, false)

    @Test fun hashIsNameLessThanAAndALetterIsNameStartsWith() {
        val hash = browseQuery(U1, id(9), ListShape.DIRECT, BrowseFilter(letter = "#"), byName, 0).toMap()
        assertEquals("A", hash["nameLessThan"])
        assertNull(hash["nameStartsWith"])
        val z = browseQuery(U1, id(9), ListShape.DIRECT, BrowseFilter(letter = "Z"), byName, 0).toMap()
        assertEquals("Z", z["nameStartsWith"])
        assertNull(z["nameLessThan"])
    }

    @Test fun noFilterSendsNoFilterKeys() {
        val q = browseQuery(U1, id(9), ListShape(setOf(ItemKind.SERIES), recursive = true), BrowseFilter(), byName, 200).toMap()
        listOf("genreIds", "studioIds", "years", "officialRatings", "tags", "nameStartsWith", "nameLessThan").forEach { assertFalse(it, q.containsKey(it)) }
        assertEquals("Series", q["includeItemTypes"])
        assertEquals("200", q["startIndex"])
        assertEquals("SortName", q["sortBy"])
        assertEquals("Ascending", q["sortOrder"])
    }

    @Test fun everyFilterValueIsOneValueAndChecked() {
        fun bad(filter: BrowseFilter) = try {
            browseQuery(U1, id(9), ListShape.DIRECT, filter, byName, 0)
            fail("accepted $filter")
        } catch (e: AppError) {
            assertEquals(AppError.Kind.UNKNOWN, e.kind)
        }
        bad(BrowseFilter(year = 1799))
        bad(BrowseFilter(year = 2201))
        bad(BrowseFilter(letter = "a"))
        bad(BrowseFilter(letter = "AB"))
        bad(BrowseFilter(letter = ""))
        bad(BrowseFilter(rating = "PG,15"))
        bad(BrowseFilter(rating = "PG|15"))
        bad(BrowseFilter(rating = "   "))
        bad(BrowseFilter(rating = "x".repeat(21)))
        bad(BrowseFilter(tag = "a,b"))
        bad(BrowseFilter(tag = "t".repeat(201)))
        try {
            browseQuery(U1, id(9), ListShape.DIRECT, BrowseFilter(genreId = "g1,g2"), byName, 0)
            fail("accepted two genres")
        } catch (e: AppError) {
            assertEquals(AppError.Kind.SERVER_NOT_FOUND, e.kind)
        }
        try {
            browseQuery(U1, id(9), ListShape.DIRECT, BrowseFilter(studioId = id(255).uppercase()), byName, 0)
            fail("accepted an unnormalised id")
        } catch (e: AppError) {
            assertEquals(AppError.Kind.SERVER_NOT_FOUND, e.kind)
        }
    }
}
