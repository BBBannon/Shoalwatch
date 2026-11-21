package app.shoalwatch.data.api

import app.shoalwatch.core.AppError
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/** `ServerApi.postBytes` (Milestone 10, a picture upload): a non-JSON body with its own type, on the same rules as every call. */
class ServerApiPostBytesTest : ServerApiTestBase() {
    private fun upload(body: ByteArray = "aGVsbG8=".toByteArray(), sendIf: () -> Boolean = { true }) = runBlocking {
        api.postBytes(session, "/Items/abc/Images/Primary", emptyList(), body, "image/jpeg", sendIf = sendIf)
    }

    @Test fun theBodyIsSentWithItsOwnTypeAndTheToken() {
        web.enqueue(MockResponse().setResponseCode(204))
        upload()
        val request = web.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/Items/abc/Images/Primary", request.path)
        assertEquals("image/jpeg", request.getHeader("Content-Type"))
        assertEquals("aGVsbG8=", request.body.readUtf8())
        assertTrue(request.getHeader("Authorization")!!.contains("Token=\"tok-1\""))
    }

    @Test fun nothingIsSentWhenTheLastCheckSaysNo() {
        assertEquals(AppError.Kind.FORBIDDEN, failure { upload(sendIf = { false }) }.kind)
        assertEquals(0, web.requestCount)
    }

    @Test fun aRefusalIsMappedAsEverywhereAndNotRetried() {
        web.enqueue(MockResponse().setResponseCode(500))
        assertEquals(AppError.Kind.SERVER_ERROR, failure { upload() }.kind)
        assertEquals(1, web.requestCount)
    }

    @Test fun offlineSendsNothing() {
        selector.answer = { null }
        assertEquals(AppError.Kind.OFFLINE, failure { upload() }.kind)
        assertEquals(0, web.requestCount)
    }
}
