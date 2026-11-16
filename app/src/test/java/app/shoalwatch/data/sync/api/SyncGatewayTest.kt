package app.shoalwatch.data.sync.api

import app.shoalwatch.U1
import app.shoalwatch.core.AppError
import app.shoalwatch.data.api.ServerApiTestBase
import app.shoalwatch.data.library.api.LibraryGatewayImpl
import app.shoalwatch.data.sync.ServerUserData
import app.shoalwatch.data.sync.UserDataUpdate
import app.shoalwatch.testId
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.TimeUnit

/** `SyncGatewayImpl` on the real `ServerApi` and OkHttp: exact requests, exact bodies, the token only in its header, and "gone" told apart from "no route". */
class SyncGatewayTest : ServerApiTestBase() {
    private val item = testId("film")
    private val gateway by lazy { SyncGatewayImpl(api, LibraryGatewayImpl(api)) }
    private val requests = mutableListOf<RecordedRequest>()

    private fun next(): RecordedRequest = (web.takeRequest(3, TimeUnit.SECONDS) ?: error("no request was sent")).also { requests += it }

    private fun assertRequest(method: String, path: String): RecordedRequest = next().also {
        assertEquals(method, it.method)
        assertEquals(path, it.requestUrl!!.encodedPath)
        assertEquals(setOf("userId"), it.requestUrl!!.queryParameterNames)
        assertEquals(U1, it.requestUrl!!.queryParameter("userId"))
    }

    private fun ok() = MockResponse().setResponseCode(204)
    private fun itemJson() = json("""{"Id":"$item","Name":"Film","Type":"Movie"}""")

    @Test fun userDataReadsTheServersCopy() = runBlocking {
        web.enqueue(json("""{"Played":true,"PlayCount":2,"PlaybackPositionTicks":50000,"IsFavorite":true,"LastPlayedDate":"2026-11-15T14:05:00.1234567Z"}"""))
        val copy = gateway.userData(session, item)
        assertEquals(ServerUserData(true, 2, 5, true, Instant.parse("2026-11-15T14:05:00.123Z").toEpochMilli()), copy)
        assertRequest("GET", "/UserItems/$item/UserData")
        tokenOnlyInItsHeader()
    }

    @Test fun updateSendsOnlyTheFieldsItMeansAsExactJson() = runBlocking {
        web.enqueue(ok())
        web.enqueue(ok())
        val at = Instant.parse("2026-11-15T14:05:00.789Z").toEpochMilli()
        gateway.update(session, item, UserDataUpdate(played = true, playCount = 2, positionMs = 0, lastPlayedAt = at))
        gateway.update(session, item, UserDataUpdate(positionMs = 42_000))
        assertEquals(
            """{"Played":true,"PlayCount":2,"PlaybackPositionTicks":0,"LastPlayedDate":"2026-11-15T14:05:00Z"}""",
            assertRequest("POST", "/UserItems/$item/UserData").body.readUtf8(),
        )
        val second = assertRequest("POST", "/UserItems/$item/UserData")
        assertEquals("""{"PlaybackPositionTicks":420000000}""", second.body.readUtf8())
        assertTrue(second.getHeader("Content-Type")!!.startsWith("application/json"))
        tokenOnlyInItsHeader()
    }

    @Test fun markPlayedUnplayedAndFavouriteUseTheirOwnRoutes() = runBlocking {
        repeat(4) { web.enqueue(ok()) }
        gateway.markPlayed(session, item)
        gateway.markUnplayed(session, item)
        gateway.favourite(session, item, on = true)
        gateway.favourite(session, item, on = false)
        assertEquals(0L, assertRequest("POST", "/UserPlayedItems/$item").bodySize)
        assertRequest("DELETE", "/UserPlayedItems/$item")
        assertRequest("POST", "/UserFavoriteItems/$item")
        assertRequest("DELETE", "/UserFavoriteItems/$item")
        tokenOnlyInItsHeader()
    }

    @Test fun aNotFoundRouteWithTheItemPresentIsAnUnknownCopy() = runBlocking {
        web.enqueue(json("{}", code = 404))
        web.enqueue(itemJson())
        assertNull(gateway.userData(session, item))
        assertRequest("GET", "/UserItems/$item/UserData")
        assertEquals("/Items/$item", next().requestUrl!!.encodedPath)
    }

    @Test fun aNotFoundRouteWithTheItemGoneFailsAsNotFound() {
        web.enqueue(json("{}", code = 404))
        web.enqueue(json("{}", code = 404))
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, failure { runBlocking { gateway.userData(session, item) } }.kind)
    }

    @Test fun anItemIsGoneOnlyWhenItIsNotFoundAndTheViewsAnswer() = runBlocking {
        web.enqueue(json("{}", code = 404))
        web.enqueue(json("""{"Items":[]}"""))
        assertTrue(gateway.itemGone(session, item))
        assertEquals("/Items/$item", next().requestUrl!!.encodedPath)
        assertEquals("/UserViews", next().requestUrl!!.encodedPath)

        web.enqueue(json("{}", code = 404))
        web.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://elsewhere.example/"))
        assertFalse("a redirect on the views: the route is gone, not the item", gateway.itemGone(session, item))

        web.enqueue(itemJson())
        assertFalse("found", gateway.itemGone(session, item))
    }

    /** Final review I1: the check that proves a 403 is about the item, not the proxy: GET /UserViews as the profile. */
    @Test fun profileAnswersAsksTheViewsAndPassesAFailureThrough() {
        web.enqueue(json("""{"Items":[]}"""))
        runBlocking { gateway.profileAnswers(session) }
        val asked = next()
        assertEquals("/UserViews", asked.requestUrl!!.encodedPath)
        web.enqueue(json("{}", code = 403))
        assertEquals(AppError.Kind.FORBIDDEN, failure { runBlocking { gateway.profileAnswers(session) } }.kind)
    }

    @Test fun forbiddenPassesThrough() {
        web.enqueue(json("{}", code = 403))
        web.enqueue(json("{}", code = 403))
        assertEquals(AppError.Kind.FORBIDDEN, failure { runBlocking { gateway.userData(session, item) } }.kind)
        assertEquals(AppError.Kind.FORBIDDEN, failure { runBlocking { gateway.update(session, item, UserDataUpdate(played = true)) } }.kind)
    }

    @Test fun anIdThatIsNotNormalisedSendsNothing() {
        for (bad in listOf(item.uppercase(), "../Users", "")) {
            assertEquals(AppError.Kind.SERVER_NOT_FOUND, failure { runBlocking { gateway.markPlayed(session, bad) } }.kind)
        }
        assertEquals(0, web.requestCount)
    }

    /** Every request so far: the token in `Authorization` only, never `api_key` or `datePlayed`. */
    private fun tokenOnlyInItsHeader() {
        assertTrue(requests.isNotEmpty())
        for (r in requests) {
            val url = r.requestUrl!!
            assertFalse("datePlayed" in url.queryParameterNames || "api_key" in url.queryParameterNames)
            assertFalse("tok-1" in url.toString())
            assertFalse("tok-1" in r.body.clone().readUtf8())
            assertTrue(r.getHeader("Authorization")!!.contains("Token=\"tok-1\""))
            assertEquals(1, r.headers.count { (_, value) -> "tok-1" in value })
        }
    }
}
