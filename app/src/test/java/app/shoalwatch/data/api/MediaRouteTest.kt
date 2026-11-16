package app.shoalwatch.data.api

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.core.AppError
import app.shoalwatch.data.playback.MediaPaths
import app.shoalwatch.data.playback.StreamSource
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.Session
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.net.spkiSha256Base64
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Media bytes: the verified address, the account's own client, and a guard that refuses everything outside `/Videos/`. */
class MediaRouteTest : ServerApiTestBase() {
    private val item = "0123456789abcdef0123456789abcdef"
    private val source = MediaPaths.directStream(item, "abc123", "etag9")

    private fun route(s: Session = session) = runBlocking { api.mediaRoute(s) }

    private fun send(route: MediaRoute, request: Request): Response = route.callFactory.newCall(request).execute()

    private fun get(route: MediaRoute, src: StreamSource = source) = send(route, Request.Builder().url(route.url(src)).build())

    /** The guard's refusal: an IOException with `AppError(kind)` in its cause chain, and nothing sent. */
    private fun refused(kind: AppError.Kind = AppError.Kind.FORBIDDEN, block: () -> Unit) {
        val before = web.requestCount
        try {
            block()
            fail("expected a refusal")
        } catch (e: IOException) {
            assertEquals(kind, AppError.from(e).kind)
        }
        assertEquals("nothing may be sent", before, web.requestCount)
    }

    private fun appError(block: () -> Unit): AppError = failure(block)

    @Test fun aMediaGetCarriesTheTokenAndTheServersCustomHeaderOnTheVerifiedAddress() {
        runBlocking { store.upsertServer(Server(S1, "Den", "12.4.0", address(), null, true, null, null, mapOf("X-Gate" to "open"))) }
        web.enqueue(MockResponse().setBody("bytes"))
        get(route()).use { assertEquals("bytes", it.body.string()) }
        val request = web.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("/Videos/$item/stream?static=true&mediaSourceId=abc123&tag=etag9", request.path)
        assertTrue(request.getHeader("Authorization")!!.contains("Token=\"tok-1\""))
        assertEquals("open", request.getHeader("X-Gate"))
    }

    @Test fun theRouteKeepsTheServersBasePath() {
        saveServer(path = "/jellyfin")
        selector.answer = { online("/jellyfin") }
        web.enqueue(MockResponse().setBody("bytes"))
        get(route()).close()
        assertEquals("/jellyfin/Videos/$item/stream", web.takeRequest(3, TimeUnit.SECONDS)!!.requestUrl!!.encodedPath)
    }

    @Test fun anotherAuthorityIsRefusedBeforeAnythingIsSent() {
        val other = MockWebServer().apply { start() }
        try {
            val r = route()
            refused { send(r, Request.Builder().url(other.url("/Videos/$item/stream")).build()).close() }
            refused { send(r, Request.Builder().url("http://localhost:${web.port}/Videos/$item/stream").build()).close() }
            refused { send(r, Request.Builder().url("http://127.0.0.1:${web.port + 1}/Videos/$item/stream").build()).close() }
            refused { send(r, Request.Builder().url("https://127.0.0.1:${web.port}/Videos/$item/stream").build()).close() }
            refused { send(r, Request.Builder().url("http://user:pw@127.0.0.1:${web.port}/Videos/$item/stream").build()).close() }
            assertEquals(0, other.requestCount)
        } finally {
            other.shutdown()
        }
    }

    @Test fun aPathOutsideVideosIsRefused() {
        val r = route()
        val base = "http://127.0.0.1:${web.port}"
        for (path in listOf("/Items/x", "/System/Info", "/", "/Videos", "/Videos/", "/videosx/$item", "/Videos/..%2FSystem/Info", "/Videos/a%2Fb",
            "/Videos/a%5Cb", "/Videos/%252e%252e/x", "/Videos/../System/Info", "/Videos/%2e%2e/System", "/x/Videos/$item")) {
            refused { send(r, Request.Builder().url(base + path).build()).close() }
        }
        refused { get(r, StreamSource("/Items/$item/Images/Primary", emptyList(), hls = false)).close() }
    }

    @Test fun theVideosSegmentMatchesInAnyCaseButTheBasePathMustMatchExactly() {
        saveServer(path = "/jellyfin")
        selector.answer = { online("/jellyfin") }
        val r = route()
        web.enqueue(MockResponse().setBody("a"))
        send(r, Request.Builder().url("http://127.0.0.1:${web.port}/jellyfin/videos/$item/main.m3u8").build()).close()
        refused { send(r, Request.Builder().url("http://127.0.0.1:${web.port}/Videos/$item/main.m3u8").build()).close() }
        refused { send(r, Request.Builder().url("http://127.0.0.1:${web.port}/JELLYFIN/videos/$item/main.m3u8").build()).close() }
        refused { send(r, Request.Builder().url("http://127.0.0.1:${web.port}/other/videos/$item/main.m3u8").build()).close() }
        assertEquals(1, web.requestCount)
    }

    @Test fun onlyGetAndHeadAreAllowed() {
        val r = route()
        val url = r.url(source)
        refused { send(r, Request.Builder().url(url).post("x".toRequestBody()).build()).close() }
        refused { send(r, Request.Builder().url(url).put("x".toRequestBody()).build()).close() }
        refused { send(r, Request.Builder().url(url).delete().build()).close() }
        web.enqueue(MockResponse())
        send(r, Request.Builder().url(url).head().build()).close()
        assertEquals("HEAD", web.takeRequest(3, TimeUnit.SECONDS)!!.method)
    }

    @Test fun aRefusedCallAlsoFailsThroughEnqueue() {
        val r = route()
        val failure = CompletableFuture<IOException>()
        val call = r.callFactory.newCall(Request.Builder().url("http://127.0.0.1:${web.port}/Items/x").build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { failure.complete(e) }
            override fun onResponse(call: Call, response: Response) { response.close(); failure.completeExceptionally(AssertionError("sent")) }
        })
        assertEquals(AppError.Kind.FORBIDDEN, AppError.from(failure.get(3, TimeUnit.SECONDS)).kind)
        assertTrue(call.isExecuted())
        assertFalse(call.clone().isExecuted())
        assertEquals(0, web.requestCount)
    }

    @Test fun mediaRouteForASignedOutAccountIsNotSignedInWithNothingSent() {
        assertEquals(AppError.Kind.NOT_SIGNED_IN, appError { route(Session(S1, U2)) }.kind)
        assertEquals(0, web.requestCount)
    }

    @Test fun aRouteWhoseAccountSignsOutLaterSendsNothingMore() {
        val r = route()
        runBlocking { store.removeAccount(S1, U1) }
        refused(AppError.Kind.NOT_SIGNED_IN) { get(r).close() }
    }

    @Test fun offlineIsOfflineWithNothingSent() {
        selector.answer = { null }
        assertEquals(AppError.Kind.OFFLINE, appError { route() }.kind)
        assertEquals(0, web.requestCount)
    }

    @Test fun anAddressNoLongerSavedIsRefusedAndReChecked() {
        selector.answer = { online("/elsewhere") }
        assertEquals(AppError.Kind.OFFLINE, appError { route() }.kind)
        assertEquals(1, selector.refreshes.get())
        assertEquals(0, web.requestCount)
    }

    @Test fun aRemovedServerIsServerNotFound() {
        runBlocking { store.removeServer(S1) }
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, appError { route() }.kind)
    }

    @Test fun onceTheVerifiedAddressIsNoLongerCurrentNothingMoreIsSent() {
        val r = route()
        selector.state.value = Connection.Checking(previous = online())
        refused(AppError.Kind.OFFLINE) { get(r).close() }
        selector.state.value = Connection.Online(address("/other"), isHome = false)
        refused(AppError.Kind.OFFLINE) { get(r).close() }
        selector.state.value = Connection.Online(address(), isHome = true) // verified again: the same address is usable again
        web.enqueue(MockResponse())
        get(r).close()
        assertEquals(1, web.requestCount)
    }

    @Test fun aRouteDoesNotOutliveAChangeOfTheServersSettings() {
        val r = route()
        saveServer(cleartext = false) // the user withdrew consent for plain http while the film was open
        refused(AppError.Kind.OFFLINE) { get(r).close() }
        saveServer(cleartext = true)
        refused(AppError.Kind.OFFLINE) { get(r).close() } // a fresh route is needed: this one's client had the old settings
        web.enqueue(MockResponse())
        get(route()).close()
        assertEquals(1, web.requestCount)
    }

    @Test fun aCallerSuppliedHostHeaderIsRefused() {
        val r = route()
        refused { send(r, Request.Builder().url(r.url(source)).header("Host", "evil.example").build()).close() }
    }

    @Test fun thePinnedSelfSignedServersMediaRequestUsesThePinAndAChangedCertificateFails() {
        val cert = HeldCertificate.Builder().commonName("lan").addSubjectAlternativeName("127.0.0.1").build()
        val tls = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
            start()
        }
        try {
            val https = ServerAddress.parse("https://127.0.0.1:${tls.port}")
            fun save(pin: String) = runBlocking { store.upsertServer(Server(S1, "Den", "12.4.0", https, null, false, pin, null, emptyMap())) }
            selector.answer = { Connection.Online(https, isHome = true) }
            save(spkiSha256Base64(cert.certificate))
            tls.enqueue(MockResponse().setBody("bytes"))
            get(route()).use { assertEquals("bytes", it.body.string()) }
            assertNotNull(tls.takeRequest(3, TimeUnit.SECONDS)!!.getHeader("Authorization"))

            save(spkiSha256Base64(HeldCertificate.Builder().build().certificate))
            val r = route()
            try {
                get(r).close()
                fail("a changed certificate must fail")
            } catch (e: IOException) {
                assertEquals(AppError.Kind.TLS_PIN_CHANGED, AppError.from(e).kind)
            }
            assertEquals(1, tls.requestCount)
        } finally {
            tls.shutdown()
        }
    }

    @Test fun aPlainHttpServerWithoutConsentIsCleartextBlocked() {
        saveServer(cleartext = false)
        refused(AppError.Kind.CLEARTEXT_BLOCKED) { get(route()).close() }
    }

    @Test fun theRequestUrlNeverCarriesACredentialEvenIfTheSourceQueryHadOne() {
        val r = route()
        val hostile = StreamSource("/videos/$item/master.m3u8", listOf("MediaSourceId" to "abc", "api_key" to "LEAK", "ApiKey" to "LEAK", "X-Emby-Token" to "LEAK"), hls = true)
        web.enqueue(MockResponse())
        get(r, hostile).close()
        val sent = web.takeRequest(3, TimeUnit.SECONDS)!!
        assertFalse(sent.path!!.contains("LEAK"))
        assertEquals("/videos/$item/master.m3u8?MediaSourceId=abc", sent.path)
    }

    @Test fun aRedirectIsReturnedToTheCallerAndNeverFollowed() {
        web.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/Videos/$item/other"))
        get(route()).use { assertEquals(302, it.code) }
        assertEquals(1, web.requestCount)
    }

    @Test fun nothingInARoutePrintsAnAddress() {
        val r = route()
        assertEquals("MediaRoute(<redacted>)", r.toString())
        assertFalse(r.callFactory.toString().contains("127.0.0.1"))
    }
}
