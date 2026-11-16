package app.shoalwatch.data.api

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.core.AppError
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** `ServerApi.postForJson`: a POST with a JSON body whose answer is decoded (PlaybackInfo), on the same rules as every call. */
class ServerApiPostForJsonTest : ServerApiTestBase() {
    private fun postThings(body: String = """{"A":1}""", retry: Boolean = false, maxBytes: Long = 4L * 1024 * 1024) =
        runBlocking { api.postForJson(session, "/Things/Info", listOf("userId" to U1), body, things(), maxBytes = maxBytes, retry = retry) }

    @Test fun theBodyIsSentAsJsonAndTheAnswerIsDecoded() {
        web.enqueue(json("""[{"Name":"a","Count":2}]"""))
        val result = postThings("""{"UserId":"x"}""")
        assertEquals("a", result.single().name)
        assertEquals(2, result.single().count)
        val request = web.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/Things/Info?userId=$U1", request.path)
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("""{"UserId":"x"}""", request.body.readUtf8())
        assertTrue(request.getHeader("Authorization")!!.contains("Token=\"tok-1\""))
    }

    @Test fun a401InvalidatesTheSignInExactlyAsGetDoes() {
        web.enqueue(MockResponse().setResponseCode(401))
        val e = failure { postThings() }
        assertEquals(AppError.Kind.UNAUTHORISED, e.kind)
        assertEquals("Your sign-in has expired. Sign in again.", e.userMessage)
        assertEquals(AppError.Kind.NOT_SIGNED_IN, failure { postThings() }.kind)
        assertEquals(1, web.requestCount)
        assertTrue(runBlocking { store.accounts(S1).first() }.none { it.userId == U1 })
    }

    @Test fun anOverLimitBodyIsAServerError() {
        web.enqueue(json("""[{"Name":"${"x".repeat(200)}"}]"""))
        assertEquals(AppError.Kind.SERVER_ERROR, failure { postThings(maxBytes = 100) }.kind)
    }

    @Test fun anUndecodableBodyIsAServerError() {
        web.enqueue(json("""{"not":"a list"}"""))
        assertEquals(AppError.Kind.SERVER_ERROR, failure { postThings() }.kind)
    }

    @Test fun aStatusIsMappedAsEverywhere() {
        web.enqueue(MockResponse().setResponseCode(403))
        assertEquals(AppError.Kind.FORBIDDEN, failure { postThings() }.kind)
        web.enqueue(MockResponse().setResponseCode(500))
        assertEquals(AppError.Kind.SERVER_ERROR, failure { postThings() }.kind)
    }

    @Test fun notRetriedByDefaultAfterAnIoFailure() {
        failWhile(AtomicBoolean(true))
        assertEquals(AppError.Kind.NETWORK, failure { postThings() }.kind)
        assertEquals(1, selector.asks.get())
        assertEquals(1, web.requestCount)
        assertEquals(1, selector.refreshes.get())
    }

    @Test fun retriedOnceWhenAsked() {
        val failing = AtomicBoolean(true)
        failWhile(failing)
        selector.answer = { n -> if (n >= 2) failing.set(false); online() }
        assertEquals("a", postThings(retry = true).single().name)
        assertEquals(2, selector.asks.get())
        assertEquals(2, web.requestCount)
    }

    @Test fun offlineSendsNothing() {
        selector.answer = { null }
        assertEquals(AppError.Kind.OFFLINE, failure { postThings() }.kind)
        assertEquals(0, web.requestCount)
    }
}
