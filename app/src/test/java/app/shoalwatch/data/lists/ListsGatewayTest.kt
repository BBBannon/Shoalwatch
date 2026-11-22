package app.shoalwatch.data.lists

import app.shoalwatch.S1
import app.shoalwatch.TestMonotonic
import app.shoalwatch.U1
import app.shoalwatch.core.AppError
import app.shoalwatch.data.api.ServerApi
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.inMemoryDb
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.lists.api.ListsGateway
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.KeyGenerator

/** Playlists and collections: every path, query and body as sent, the answers as mapped, refusals, hostile answers and the index (Milestone 11). */
@RunWith(RobolectricTestRunner::class)
class ListsGatewayTest {
    private inner class Online(val online: Connection.Online) : ServerConnections {
        override fun connection(serverId: String): StateFlow<Connection> = MutableStateFlow(online)
        override suspend fun awaitOnline(serverId: String, timeoutMs: Long): Connection.Online = online
        override fun refresh() {}
        override fun refresh(serverId: String) {}
        override fun refreshIfCurrent(serverId: String, failed: Connection.Online) {}
    }

    private val web = MockWebServer()
    private val session = Session(S1, U1)
    private val clock = TestMonotonic()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val clients = ServerClients(HttpClientFactory(HandshakeCertificates.Builder().build().trustManager, { error("no client certificates here") }))
    private lateinit var db: AppDatabase
    private lateinit var store: ServerStore
    private lateinit var lists: ListsGateway

    /** Every request the server saw, in order, as method and path with query. */
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
        lists = ListsGateway(api, clock)
        runBlocking {
            store.upsertServer(Server(S1, "Den", "12.4.0", address, null, true, null, null, emptyMap()))
            store.upsertAccount(Account(S1, U1, "ben", "tok-1"), makeActive = true)
            store.warmTokens()
        }
    }

    @After fun close() { db.close(); web.shutdown() }

    private fun json(body: String, code: Int = 200) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)
    private fun noContent() = MockResponse().setResponseCode(204)
    private fun id(n: Int) = "%032x".format(n)
    private fun item(n: Int, type: String = "Movie", extra: String = "") = """{"Id":"${id(n)}","Name":"Item $n","Type":"$type"$extra}"""
    private fun page(vararg items: String) = """{"Items":[${items.joinToString(",")}],"TotalRecordCount":${items.size}}"""
    private fun url(r: RecordedRequest): HttpUrl = r.requestUrl!!
    private fun requests() = synchronized(seen) { seen.toList() }
    private fun last() = requests().last()

    private fun failure(block: suspend () -> Unit): Throwable {
        try {
            runBlocking { block() }
        } catch (e: Throwable) {
            return e
        }
        fail("expected a failure")
        error("unreachable")
    }

    private fun refusal(block: suspend () -> Unit): AppError = failure(block) as AppError

    /** Answers `/Users/Me` as [me] and anything else by [other]. */
    private fun answering(me: Int, other: (RecordedRequest) -> MockResponse) {
        route = { r -> if (r.path == "/Users/Me") (if (me == 200) json("""{"Id":"$U1"}""") else MockResponse().setResponseCode(me)) else other(r) }
    }

    // ---- playlists

    @Test fun playlistsAreListedForTheProfileSortedByName() {
        route = { json(page(item(1, "Playlist", ""","ChildCount":3"""), item(2, "Playlist"))) }
        val got = runBlocking { lists.playlists(session) }
        assertEquals(listOf(id(1), id(2)), got.map { it.item.id })
        assertEquals(ItemKind.PLAYLIST, got[0].item.kind)
        assertEquals(3, got[0].item.childCount)
        val u = url(last())
        assertEquals("GET", last().method)
        assertEquals("/Items", u.encodedPath)
        assertEquals(U1, u.queryParameter("userId"))
        assertEquals("Playlist", u.queryParameter("includeItemTypes"))
        assertEquals("true", u.queryParameter("recursive"))
        assertEquals("SortName", u.queryParameter("sortBy"))
        assertEquals("ChildCount", u.queryParameter("fields"))
        assertEquals("true", u.queryParameter("enableImages"))
    }

    @Test fun anOverLongPlaylistListIsCutAtFiveHundredAndBadElementsAreSkipped() {
        val items = (1..600).map { item(it, "Playlist") } + """{"Id":"nope","Name":"bad"}"""
        route = { json(page(*items.toTypedArray())) }
        assertEquals(500, runBlocking { lists.playlists(session) }.size)
    }

    private fun entry(n: Int, entryId: String?, extra: String = "") =
        item(n, "Movie", (if (entryId != null) ""","PlaylistItemId":"$entryId"""" else "") + extra)

    private fun playlistRoute(pages: (Int) -> String, canDelete: String = "true", own: String? = null) {
        route = { r ->
            val u = url(r)
            when {
                u.encodedPath == "/Items/${id(900)}" -> json(own ?: item(900, "Playlist", ""","CanDelete":$canDelete"""))
                u.encodedPath == "/Playlists/${id(900)}/Items" -> json(pages(u.queryParameter("startIndex")!!.toInt()))
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    @Test fun aPlaylistIsReadWithItsOwnItemThenItsEntriesPagedAtTwoHundred() {
        playlistRoute({ start ->
            when (start) {
                0 -> page(*(1..200).map { entry(it, "e$it") }.toTypedArray())
                else -> page(entry(201, "e201"), entry(202, "e202"))
            }
        })
        val p = runBlocking { lists.playlist(session, id(900)) }
        assertEquals(id(900), p.item.id)
        assertTrue(p.canDelete)
        assertEquals(202, p.entries.size)
        assertEquals("e1", p.entries.first().entryId)
        assertEquals(id(202), p.entries.last().item.item.id)
        val all = requests()
        assertEquals(3, all.size)
        assertEquals("/Items/${id(900)}", url(all[0]).encodedPath)
        assertEquals(U1, url(all[0]).queryParameter("userId"))
        for ((i, start) in listOf(0, 200).withIndex()) {
            val u = url(all[i + 1])
            assertEquals("/Playlists/${id(900)}/Items", u.encodedPath)
            assertEquals(U1, u.queryParameter("userId"))
            assertEquals(start.toString(), u.queryParameter("startIndex"))
            assertEquals("200", u.queryParameter("limit"))
        }
    }

    @Test fun aPlaylistOfThousandsStopsAtTwoThousandEntriesAndTenRequests() {
        playlistRoute({ start -> page(*(start + 1..start + 200).map { entry(it, "e$it") }.toTypedArray()) })
        val p = runBlocking { lists.playlist(session, id(900)) }
        assertEquals(2_000, p.entries.size)
        assertEquals(1 + 10, requests().size)
    }

    @Test fun anEntryWithABadIdIsDroppedNeverKeptAsAPathSegment() {
        val hostile = listOf("a/b", "..", "a\\u0000b", "a b", "x".repeat(65), "", "a?b", "../../Users", "e\\u202Ex")
        playlistRoute({
            page(*(hostile.mapIndexed { i, h -> entry(i + 1, h) } + entry(50, null) + entry(60, "good-1") + entry(61, "good-1") + entry(62, "Good-2")).toTypedArray())
        })
        val p = runBlocking { lists.playlist(session, id(900)) }
        assertEquals("a repeated entry id is dropped, the same item twice is not", listOf("good-1", "Good-2"), p.entries.map { it.entryId })
    }

    @Test fun theSameItemTwiceIsTwoEntries() {
        playlistRoute({ page(entry(5, "e-a"), entry(5, "e-b")) })
        assertEquals(listOf("e-a", "e-b"), runBlocking { lists.playlist(session, id(900)) }.entries.map { it.entryId })
    }

    @Test fun controlCharactersInANameAreCleanedBeforeTheScreenSeesThem() {
        playlistRoute({ page(entry(1, "e1")) }, own = """{"Id":"${id(900)}","Name":"Fri\u0000day\n‮night","Type":"Playlist","CanDelete":true}""")
        assertEquals("Fri day night", runBlocking { lists.playlist(session, id(900)) }.item.name)
    }

    @Test fun aPlaylistAnswerForADifferentItemIsAServerError() {
        playlistRoute({ page() }, own = item(901, "Playlist"))
        assertEquals(AppError.Kind.SERVER_ERROR, refusal { lists.playlist(session, id(900)) }.kind)
    }

    @Test fun canDeleteIsOnlyTrueForARealTrue() {
        for (said in listOf("false", "\"true\"", "1", "null", "[true]")) {
            playlistRoute({ page() }, canDelete = said)
            assertFalse(said, runBlocking { lists.playlist(session, id(900)) }.canDelete)
        }
    }

    @Test fun aPlaylistIdThatIsNotNormalisedSendsNothing() {
        for (bad in listOf("1a9f2c3e-4d5a-6b7c-8d9e-0f1a2b3c4d5e", "../x", "ABCDEF" + "0".repeat(26), "")) {
            assertEquals(AppError.Kind.SERVER_NOT_FOUND, refusal { lists.playlist(session, bad) }.kind)
        }
        assertEquals(0, requests().size)
    }

    // ---- changes to playlists

    @Test fun createPlaylistPostsTheNamedPrivateListOnceWithTheUsersId() {
        route = { noContent() }
        runBlocking { lists.createPlaylist(session, "  Friday  night ", listOf(id(1), id(2))) }
        assertEquals(1, requests().size)
        val r = last()
        assertEquals("POST", r.method)
        assertEquals("/Playlists", url(r).encodedPath)
        val body = Json.parseToJsonElement(r.body.readUtf8()).jsonObject
        assertEquals(setOf("Name", "Ids", "UserId", "IsPublic"), body.keys)
        assertEquals("Friday night", body["Name"]!!.jsonPrimitive.content)
        assertEquals(listOf(id(1), id(2)), body["Ids"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals(U1, body["UserId"]!!.jsonPrimitive.content)
        assertEquals("false", body["IsPublic"]!!.jsonPrimitive.content)
    }

    @Test fun namesAreOneToTwoHundredVisibleCharactersOrNothingIsSent() {
        route = { noContent() }
        for (bad in listOf("", "   ", "\u0000\n‮", "x".repeat(201))) {
            assertEquals(AppError.Kind.UNKNOWN, refusal { lists.createPlaylist(session, bad, listOf(id(1))) }.kind)
            assertEquals(AppError.Kind.UNKNOWN, refusal { lists.renamePlaylist(session, id(900), bad) }.kind)
            assertEquals(AppError.Kind.UNKNOWN, refusal { lists.createCollection(session, bad, listOf(id(1))) }.kind)
        }
        assertEquals(0, requests().size)
        runBlocking { lists.createPlaylist(session, "x".repeat(200), listOf(id(1))) }
        assertEquals(1, requests().size)
    }

    @Test fun createPlaylistRefusesABadItemId() {
        route = { noContent() }
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, refusal { lists.createPlaylist(session, "A", listOf(id(1), "../x")) }.kind)
        assertEquals(0, requests().size)
    }

    @Test fun renamePostsOnlyTheName() {
        route = { noContent() }
        runBlocking { lists.renamePlaylist(session, id(900), "Saturday") }
        assertEquals("/Playlists/${id(900)}", url(last()).encodedPath)
        assertEquals("POST", last().method)
        assertEquals("""{"Name":"Saturday"}""", last().body.readUtf8())
    }

    @Test fun addPostsTheIdsCommaSeparatedWithTheUser() {
        route = { noContent() }
        runBlocking { lists.addToPlaylist(session, id(900), listOf(id(1), id(2))) }
        val u = url(last())
        assertEquals("POST", last().method)
        assertEquals("/Playlists/${id(900)}/Items", u.encodedPath)
        assertEquals("${id(1)},${id(2)}", u.queryParameter("ids"))
        assertEquals(U1, u.queryParameter("userId"))
        assertEquals(1, requests().size)
    }

    @Test fun removeDeletesByEntryIdsAndNeverSendsABadOne() {
        route = { noContent() }
        runBlocking { lists.removeFromPlaylist(session, id(900), listOf("e-1", "E2")) }
        assertEquals("DELETE", last().method)
        assertEquals("/Playlists/${id(900)}/Items", url(last()).encodedPath)
        assertEquals("e-1,E2", url(last()).queryParameter("entryIds"))
        for (bad in listOf("a/b", "..", "a,b", "a\u0000b")) {
            assertEquals(AppError.Kind.SERVER_NOT_FOUND, refusal { lists.removeFromPlaylist(session, id(900), listOf("ok", bad)) }.kind)
        }
        assertEquals(1, requests().size)
    }

    @Test fun moveIsOnePostToMoveWithTheNewIndex() {
        route = { noContent() }
        runBlocking { lists.moveInPlaylist(session, id(900), "e-7", 3) }
        assertEquals("POST", last().method)
        assertEquals("/Playlists/${id(900)}/Items/e-7/Move/3", url(last()).encodedPath)
        assertEquals(AppError.Kind.UNKNOWN, refusal { lists.moveInPlaylist(session, id(900), "e-7", -1) }.kind)
        assertEquals(AppError.Kind.UNKNOWN, refusal { lists.moveInPlaylist(session, id(900), "e-7", 2_000) }.kind)
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, refusal { lists.moveInPlaylist(session, id(900), "a/b", 1) }.kind)
        assertEquals(1, requests().size)
    }

    // ---- deleting a playlist

    @Test fun deleteReadsCanDeleteThenDeletesOnce() {
        route = { r -> if (r.method == "DELETE") noContent() else json(item(900, "Playlist", ""","CanDelete":true""")) }
        runBlocking { lists.deletePlaylist(session, id(900)) }
        val all = requests()
        assertEquals(listOf("GET", "DELETE"), all.map { it.method })
        assertEquals("/Items/${id(900)}", url(all[0]).encodedPath)
        assertEquals(U1, url(all[0]).queryParameter("userId"))
        assertEquals("/Items/${id(900)}", url(all[1]).encodedPath)
    }

    @Test fun deleteIsRefusedWithNothingSentWhenCanDeleteIsNotTrue() {
        route = { json(item(900, "Playlist", ""","CanDelete":false""")) }
        assertEquals(AppError.Kind.FORBIDDEN, refusal { lists.deletePlaylist(session, id(900)) }.kind)
        assertEquals(listOf("GET"), requests().map { it.method })
    }

    @Test fun aDeleteRefusedWith401IsCheckedAndTheSignInKept() {
        answering(me = 200) { r -> if (r.method == "DELETE") MockResponse().setResponseCode(401) else json(item(900, "Playlist", ""","CanDelete":true""")) }
        assertEquals(AppError.Kind.FORBIDDEN, refusal { lists.deletePlaylist(session, id(900)) }.kind)
        assertEquals(listOf("GET /Items/${id(900)}", "DELETE /Items/${id(900)}", "GET /Users/Me"), requests().map { it.method + " " + url(it).encodedPath })
        assertEquals("tok-1", store.cachedToken(S1, U1))
    }

    // ---- refusals

    @Test fun a403IsForbiddenSentOnceAndTheSignInKept() {
        route = { MockResponse().setResponseCode(403) }
        assertEquals(AppError.Kind.FORBIDDEN, refusal { lists.renamePlaylist(session, id(900), "X") }.kind)
        assertEquals(1, requests().size)
        assertEquals("tok-1", store.cachedToken(S1, U1))
    }

    @Test fun everyChangeAnswered401IsCheckedWithUsersMeThenIsAForbiddenAndNeverSignsOut() {
        val changes: List<Pair<String, suspend () -> Unit>> = listOf(
            "create" to { lists.createPlaylist(session, "A", listOf(id(1))) },
            "rename" to { lists.renamePlaylist(session, id(900), "A") },
            "add" to { lists.addToPlaylist(session, id(900), listOf(id(1))) },
            "remove" to { lists.removeFromPlaylist(session, id(900), listOf("e1")) },
            "move" to { lists.moveInPlaylist(session, id(900), "e1", 1) },
            "createCollection" to { lists.createCollection(session, "A", listOf(id(1))) },
            "addToCollection" to { lists.addToCollection(session, id(800), listOf(id(1))) },
            "removeFromCollection" to { lists.removeFromCollection(session, id(800), listOf(id(1))) },
        )
        for ((name, change) in changes) {
            synchronized(seen) { seen.clear() }
            answering(me = 200) { MockResponse().setResponseCode(401) }
            assertEquals(name, AppError.Kind.FORBIDDEN, refusal(change).kind)
            val sent = requests()
            assertEquals("$name sent once, then checked", 2, sent.size)
            assertEquals(name, "/Users/Me", url(sent[1]).encodedPath)
            assertEquals(name, "tok-1", store.cachedToken(S1, U1))
        }
    }

    @Test fun a401ThatIsTheSignInEndingSignsOut() {
        answering(me = 401) { MockResponse().setResponseCode(401) }
        assertEquals(AppError.Kind.UNAUTHORISED, refusal { lists.addToPlaylist(session, id(900), listOf(id(1))) }.kind)
        assertEquals(null, store.cachedToken(S1, U1))
    }

    // ---- collections

    @Test fun collectionsAreListedAsBoxSetsByName() {
        route = { json(page(item(1, "BoxSet"), item(2, "BoxSet"))) }
        assertEquals(listOf(id(1), id(2)), runBlocking { lists.collections(session) }.map { it.item.id })
        val u = url(last())
        assertEquals("/Items", u.encodedPath)
        assertEquals("BoxSet", u.queryParameter("includeItemTypes"))
        assertEquals("true", u.queryParameter("recursive"))
        assertEquals("SortName", u.queryParameter("sortBy"))
    }

    @Test fun createCollectionPostsNameIdsAndUnlocked() {
        route = { noContent() }
        runBlocking { lists.createCollection(session, "Sequels", listOf(id(1), id(2))) }
        val u = url(last())
        assertEquals("POST", last().method)
        assertEquals("/Collections", u.encodedPath)
        assertEquals("Sequels", u.queryParameter("name"))
        assertEquals("${id(1)},${id(2)}", u.queryParameter("ids"))
        assertEquals("false", u.queryParameter("isLocked"))
        assertEquals(1, requests().size)
    }

    @Test fun addAndRemoveGoToCollectionsItemsWithTheIds() {
        route = { noContent() }
        runBlocking {
            lists.addToCollection(session, id(800), listOf(id(1), id(2)))
            lists.removeFromCollection(session, id(800), listOf(id(2)))
        }
        val (add, remove) = requests()
        assertEquals("POST", add.method)
        assertEquals("DELETE", remove.method)
        assertEquals("/Collections/${id(800)}/Items", url(add).encodedPath)
        assertEquals("/Collections/${id(800)}/Items", url(remove).encodedPath)
        assertEquals("${id(1)},${id(2)}", url(add).queryParameter("ids"))
        assertEquals(id(2), url(remove).queryParameter("ids"))
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, refusal { lists.addToCollection(session, "../x", listOf(id(1))) }.kind)
        assertEquals(AppError.Kind.UNKNOWN, refusal { lists.addToCollection(session, id(800), emptyList()) }.kind)
        assertEquals(2, requests().size)
    }

    // ---- the collection index

    /** When set, a collection's children are answered only after it is released; [started] counts down at the first such read. */
    private var gate: java.util.concurrent.CountDownLatch? = null
    private val started = java.util.concurrent.CountDownLatch(1)

    /** [collections] by id, each with its children (ids). */
    private fun indexRoute(collections: Map<Int, List<Int>>, childCap: Int = Int.MAX_VALUE) {
        route = { r ->
            val u = url(r)
            val parent = u.queryParameter("parentId")
            when {
                u.encodedPath == "/Items" && parent != null -> {
                    started.countDown()
                    gate?.await(5, java.util.concurrent.TimeUnit.SECONDS)
                    val kids = collections.entries.first { id(it.key) == parent }.value.take(childCap)
                    json(page(*kids.map { item(it) }.toTypedArray()))
                }
                u.encodedPath == "/Items" -> json(page(*collections.keys.map { item(it, "BoxSet") }.toTypedArray()))
                else -> noContent()
            }
        }
    }

    private fun partOf(n: Int) = runBlocking { lists.collectionsOf(session, id(n)) }.map { it.id }

    @Test fun theIndexFindsTheCollectionsHoldingAnItemAndReadsOnlyOnce() {
        indexRoute(mapOf(800 to listOf(1, 2), 801 to listOf(2, 3), 802 to emptyList()))
        assertEquals(listOf(id(800), id(801)), partOf(2))
        assertEquals(listOf(id(801)), partOf(3))
        assertEquals(emptyList<String>(), partOf(99))
        val all = requests()
        assertEquals("one list and one read per collection, once", 1 + 3, all.size)
        // The children are read a few collections at a time, so in no set order.
        val child = all.map(::url).single { it.queryParameter("parentId") == id(800) }
        assertEquals("/Items", child.encodedPath)
        assertEquals(null, child.queryParameter("fields"))
        assertEquals("false", child.queryParameter("enableImages"))
        assertEquals("500", child.queryParameter("limit"))
    }

    @Test fun theIndexKeepsAtMostAHundredCollectionsAndFiveHundredChildrenEach() {
        val many = (1..110).associate { 1000 + it to listOf(1) }
        indexRoute(many)
        assertEquals(100, partOf(1).size)
        assertEquals("one list and 100 child reads", 101, requests().size)
        synchronized(seen) { seen.clear() }
        clock.ms += ListsLimits.INDEX_TTL_MS
        val big = mapOf(800 to (1..600).toList())
        indexRoute(big)
        assertEquals(listOf(id(800)), partOf(500))
        assertEquals("the 501st child is not kept", emptyList<String>(), partOf(501))
    }

    /** Final review I1: a server with many collections is read a few at a time, never one after another and never all at once. */
    @Test fun theIndexReadsFourCollectionsAtATimeAndKeepsTheirOrder() {
        val inFlight = java.util.concurrent.atomic.AtomicInteger()
        val most = java.util.concurrent.atomic.AtomicInteger()
        val collections = (1..12).associate { 800 + it to listOf(1) }
        indexRoute(collections)
        val listed = route
        route = { r ->
            if (url(r).queryParameter("parentId") == null) listed(r) else {
                most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
                Thread.sleep(50)
                inFlight.decrementAndGet()
                listed(r)
            }
        }
        assertEquals("in the order they were listed", collections.keys.map(::id), partOf(1))
        assertEquals("one list and a read per collection", 1 + 12, requests().size)
        assertEquals(ListsLimits.INDEX_READS_AT_ONCE, most.get())
    }

    @Test fun theIndexLastsTenMinutesOnTheMonotonicClock() {
        indexRoute(mapOf(800 to listOf(1)))
        partOf(1)
        clock.ms += ListsLimits.INDEX_TTL_MS - 1
        partOf(1)
        assertEquals(2, requests().size)
        clock.ms += 1
        indexRoute(mapOf(800 to listOf(5)))
        assertEquals(emptyList<String>(), partOf(1))
        assertEquals(listOf(id(800)), partOf(5))
        assertEquals("read again after expiry", 4, requests().size)
    }

    @Test fun anAddOrRemoveMadeThroughListsUpdatesTheIndexAtOnce() {
        indexRoute(mapOf(800 to listOf(1)))
        assertEquals(emptyList<String>(), partOf(7))
        runBlocking { lists.addToCollection(session, id(800), listOf(id(7))) }
        assertEquals(listOf(id(800)), partOf(7))
        runBlocking { lists.removeFromCollection(session, id(800), listOf(id(1))) }
        assertEquals(emptyList<String>(), partOf(1))
        assertEquals("list, one child read, the add, the remove: no re-read", 4, requests().size)
    }

    @Test fun aRefusedAddLeavesTheIndexAlone() {
        indexRoute(mapOf(800 to listOf(1)))
        partOf(1)
        route = { MockResponse().setResponseCode(403) }
        refusal { lists.addToCollection(session, id(800), listOf(id(7))) }
        indexRoute(mapOf(800 to listOf(1)))
        assertEquals(emptyList<String>(), partOf(7))
    }

    @Test fun makingACollectionMakesTheNextAskReadAgain() {
        indexRoute(mapOf(800 to listOf(1)))
        partOf(1)
        runBlocking { lists.createCollection(session, "New", listOf(id(1))) }
        indexRoute(mapOf(800 to listOf(1), 801 to listOf(1)))
        assertEquals(listOf(id(800), id(801)), partOf(1))
    }

    @Test fun aFailedIndexReadKeepsNothingAndIsTriedAgain() {
        route = { MockResponse().setResponseCode(500) }
        assertEquals(AppError.Kind.SERVER_ERROR, refusal { lists.collectionsOf(session, id(1)) }.kind)
        indexRoute(mapOf(800 to listOf(1)))
        assertEquals(listOf(id(800)), partOf(1))
    }

    @Test fun anIndexItemIdMustBeNormalised() {
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, refusal { lists.collectionsOf(session, "../x") }.kind)
        assertEquals(0, requests().size)
    }

    // ---- limits as AppError, and the index under concurrency

    @Test fun anEmptyOrOversizedIdListIsRefusedWithNothingSent() {
        route = { noContent() }
        val tooMany = (1..501).map { id(it) }
        assertEquals(AppError.Kind.UNKNOWN, refusal { lists.addToPlaylist(session, id(900), emptyList()) }.kind)
        assertEquals(AppError.Kind.UNKNOWN, refusal { lists.addToCollection(session, id(800), emptyList()) }.kind)
        assertEquals(AppError.Kind.UNKNOWN, refusal { lists.removeFromCollection(session, id(800), emptyList()) }.kind)
        assertEquals(AppError.Kind.UNKNOWN, refusal { lists.removeFromPlaylist(session, id(900), emptyList()) }.kind)
        assertEquals(AppError.Kind.UNKNOWN, refusal { lists.addToPlaylist(session, id(900), tooMany) }.kind)
        assertEquals(AppError.Kind.UNKNOWN, refusal { lists.createPlaylist(session, "A", tooMany) }.kind)
        assertEquals(AppError.Kind.UNKNOWN, refusal { lists.removeFromPlaylist(session, id(900), tooMany.map { "e$it" }) }.kind)
        assertEquals(0, requests().size)
        runBlocking { lists.addToPlaylist(session, id(900), tooMany.take(500)) }
        runBlocking { lists.createPlaylist(session, "A", emptyList()) }
        assertEquals(2, requests().size)
    }

    @Test fun anAddToAFullCollectionIsStillRemembered() {
        indexRoute(mapOf(800 to (1..600).toList()))
        assertEquals(emptyList<String>(), partOf(700))
        runBlocking { lists.addToCollection(session, id(800), listOf(id(700))) }
        assertEquals(listOf(id(800)), partOf(700))
    }

    /** Runs [change] while the index read is held open at its first child read, then lets the read finish; returns that read's answer. */
    private fun duringRead(itemN: Int, change: suspend () -> Unit): List<String> {
        gate = java.util.concurrent.CountDownLatch(1)
        return runBlocking {
            val read = async(Dispatchers.IO) { lists.collectionsOf(session, id(itemN)).map { it.id } }
            withContext(Dispatchers.IO) { assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            change()
            gate!!.countDown()
            read.await()
        }.also { gate = null }
    }

    @Test fun severalCallersAtOnceMakeOneReadOnly() {
        indexRoute(mapOf(800 to listOf(1), 801 to listOf(1)))
        gate = java.util.concurrent.CountDownLatch(1)
        val answers = runBlocking {
            val reads = (1..4).map { async(Dispatchers.IO) { lists.collectionsOf(session, id(1)).map { it.id } } }
            withContext(Dispatchers.IO) { assertTrue(started.await(5, java.util.concurrent.TimeUnit.SECONDS)) }
            gate!!.countDown()
            reads.map { it.await() }
        }
        assertTrue(answers.all { it == listOf(id(800), id(801)) })
        assertEquals("one list and two child reads, not four times that", 3, requests().size)
    }

    @Test fun anAddMadeWhileTheIndexIsBeingReadSurvives() {
        indexRoute(mapOf(800 to listOf(1)))
        val answer = duringRead(7) { lists.addToCollection(session, id(800), listOf(id(7))) }
        assertEquals(listOf(id(800)), answer)
        assertEquals(listOf(id(800)), partOf(7))
    }

    @Test fun aRemoveMadeWhileTheIndexIsBeingReadSurvives() {
        indexRoute(mapOf(800 to listOf(1, 2)))
        val answer = duringRead(1) { lists.removeFromCollection(session, id(800), listOf(id(1))) }
        assertEquals(emptyList<String>(), answer)
        assertEquals(emptyList<String>(), partOf(1))
        assertEquals(listOf(id(800)), partOf(2))
    }

    @Test fun aForgetMadeWhileTheIndexIsBeingReadIsNotUndoneByTheStaleRead() {
        indexRoute(mapOf(800 to listOf(1)))
        duringRead(1) { lists.createCollection(session, "New", listOf(id(1))) }
        val before = requests().size
        indexRoute(mapOf(800 to listOf(1), 801 to listOf(1)))
        assertEquals("read again, not the stale snapshot", listOf(id(800), id(801)), partOf(1))
        assertTrue(requests().size > before)
    }
}
