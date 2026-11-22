package app.shoalwatch.data.api

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.core.AppError
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * `ServerApi.get` and `post` take the same option (Milestone 11), tested at the end.
 * `ServerApi.delete(verifyUnauthorised = true)` (Milestone 10 review, finding 1): Jellyfin answers `DELETE /Items/{id}` with 401 when
 * the item may not be deleted by this user, so for that call a 401 is checked with `GET /Users/Me` before the sign-in is dropped.
 */
class ServerApiVerifyUnauthorisedTest : ServerApiTestBase() {
    private fun answer(delete: Int, me: Int, other: Int = delete) {
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when {
                request.method == "DELETE" -> MockResponse().setResponseCode(delete)
                request.path == "/Things" -> MockResponse().setResponseCode(other)
                request.path == "/Users/Me" -> if (me == 200) json("""{"Id":"$U1"}""") else MockResponse().setResponseCode(me)
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    private fun delete(verify: Boolean) = runBlocking {
        api.delete(session, "/Items/abc", retry = false, sendOnce = true, verifyUnauthorised = verify)
    }

    @Test fun aRefusedDeleteWithAGoodSignInSaysTheServerRefusedAndKeepsTheSignIn() {
        answer(delete = 401, me = 200)
        val e = failure { delete(verify = true) }
        assertEquals(AppError.Kind.FORBIDDEN, e.kind)
        assertEquals("The server refused to do that.", e.userMessage)
        assertEquals("tok-1", store.cachedToken(S1, U1))
        assertEquals("DELETE", web.takeRequest(3, TimeUnit.SECONDS)!!.method)
        assertEquals("/Users/Me", web.takeRequest(3, TimeUnit.SECONDS)!!.path)
        assertEquals("the DELETE is not sent again", 2, web.requestCount)
    }

    @Test fun aRefusedDeleteWithADeadSignInSignsOutAsEverywhere() {
        answer(delete = 401, me = 401)
        val e = failure { delete(verify = true) }
        assertEquals(AppError.Kind.UNAUTHORISED, e.kind)
        assertNull(store.cachedToken(S1, U1))
        assertEquals(2, web.requestCount)
    }

    @Test fun aCheckThatCannotBeMadeKeepsTheSignIn() {
        answer(delete = 401, me = 500)
        assertEquals(AppError.Kind.FORBIDDEN, failure { delete(verify = true) }.kind)
        assertEquals("tok-1", store.cachedToken(S1, U1))
    }

    @Test fun withoutTheOptionA401StillSignsOut() {
        answer(delete = 401, me = 200)
        assertEquals(AppError.Kind.UNAUTHORISED, failure { delete(verify = false) }.kind)
        assertNull(store.cachedToken(S1, U1))
        assertEquals("nothing is asked", 1, web.requestCount)
    }

    @Test fun otherAnswersAreMappedAsEverywhere() {
        answer(delete = 204, me = 200)
        delete(verify = true)
        assertEquals(1, web.requestCount)
        answer(delete = 403, me = 200)
        assertEquals(AppError.Kind.FORBIDDEN, failure { delete(verify = true) }.kind)
        assertEquals(2, web.requestCount)
    }

    private fun post(verify: Boolean) = runBlocking { api.post(session, "/Things", verifyUnauthorised = verify) }

    private fun get(verify: Boolean) = runBlocking {
        api.get(session, "/Things", deserializer = kotlinx.serialization.json.JsonElement.serializer(), verifyUnauthorised = verify)
    }

    @Test fun aRefusedPostOrGetWithAGoodSignInSaysTheServerRefusedAndKeepsTheSignIn() {
        for ((method, call) in listOf<Pair<String, (Boolean) -> Unit>>("POST" to { post(it) }, "GET" to { get(it) })) {
            answer(delete = 204, me = 200, other = 401)
            val e = failure { call(true) }
            assertEquals(method, AppError.Kind.FORBIDDEN, e.kind)
            assertEquals("The server refused to do that.", e.userMessage)
            assertEquals("tok-1", store.cachedToken(S1, U1))
            assertEquals(method, web.takeRequest(3, TimeUnit.SECONDS)!!.method)
            assertEquals("/Users/Me", web.takeRequest(3, TimeUnit.SECONDS)!!.path)
        }
    }

    @Test fun aRefusedPostOrGetWithADeadSignInSignsOut() {
        answer(delete = 204, me = 401, other = 401)
        assertEquals(AppError.Kind.UNAUTHORISED, failure { post(true) }.kind)
        assertNull(store.cachedToken(S1, U1))
    }

    @Test fun postAndGetWithoutTheOptionA401StillSignsOut() {
        for (call in listOf<(Boolean) -> Unit>({ post(it) }, { get(it) })) {
            answer(delete = 204, me = 200, other = 401)
            runBlocking {
                store.upsertAccount(app.shoalwatch.data.servers.Account(S1, U1, "ben", "tok-1"), makeActive = true)
                store.warmTokens()
            }
            assertEquals(AppError.Kind.UNAUTHORISED, failure { call(false) }.kind)
            assertNull(store.cachedToken(S1, U1))
        }
    }
}
