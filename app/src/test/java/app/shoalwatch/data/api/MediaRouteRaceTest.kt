package app.shoalwatch.data.api

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.core.AppError
import app.shoalwatch.data.playback.MediaPaths
import app.shoalwatch.data.servers.Connection
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** A route that goes stale between making a call and sending it, or while it is being made, sends nothing. */
class MediaRouteRaceTest : ServerApiTestBase() {
    private val source = MediaPaths.directStream("0123456789abcdef0123456789abcdef", "abc123", null)

    private fun route() = runBlocking { api.mediaRoute(session) }

    private fun call(r: MediaRoute): Call = r.callFactory.newCall(Request.Builder().url(r.url(source)).build())

    private fun refused(kind: AppError.Kind, block: () -> Unit) {
        try {
            block()
            fail("expected a refusal")
        } catch (e: IOException) {
            assertEquals(kind, AppError.from(e).kind)
        }
        assertEquals("nothing may be sent", 0, web.requestCount)
    }

    @Test fun aCallMadeBeforeASignOutIsRefusedWhenExecuted() {
        val c = call(route())
        runBlocking { store.removeAccount(S1, U1) }
        refused(AppError.Kind.NOT_SIGNED_IN) { c.execute().close() }
    }

    @Test fun aCallMadeBeforeANetworkChangeIsRefusedWhenEnqueued() {
        val c = call(route())
        selector.state.value = Connection.Checking(previous = online())
        val failure = CompletableFuture<IOException>()
        c.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { failure.complete(e) }
            override fun onResponse(call: Call, response: Response) { response.close(); failure.completeExceptionally(AssertionError("sent")) }
        })
        assertEquals(AppError.Kind.OFFLINE, AppError.from(failure.get(3, TimeUnit.SECONDS)).kind)
        assertEquals(0, web.requestCount)
    }

    @Test fun aCloneOfAPassedCallIsCheckedAgain() {
        val c = call(route())
        saveServer(cleartext = true) // a settings save: the route's client is no longer current
        refused(AppError.Kind.OFFLINE) { c.clone().execute().close() }
    }

    @Test fun aPassedCallStillSendsWhileTheRouteIsCurrent() {
        web.enqueue(MockResponse().setBody("bytes"))
        val c = call(route())
        c.clone().execute().use { assertEquals("bytes", it.body.string()) }
        assertEquals(1, web.requestCount)
    }
}
