package app.shoalwatch.data.api

import app.shoalwatch.S1
import app.shoalwatch.core.AppError
import app.shoalwatch.data.playback.MediaPaths
import app.shoalwatch.data.playback.StreamSource
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.Session
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.net.spkiSha256Base64
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The download route: the media route's guard, plus exactly `/Items/<this item>/Download` and nothing else. */
class DownloadRouteTest : ServerApiTestBase() {
    private val item = "0123456789abcdef0123456789abcdef"
    private val other = "fedcba9876543210fedcba9876543210"

    private fun route(s: Session = session, id: String = item) = runBlocking { api.downloadRoute(s, id) }

    private fun send(route: MediaRoute, request: Request): Response = route.callFactory.newCall(request).execute()

    private fun get(route: MediaRoute, path: String) = send(route, Request.Builder().url("http://127.0.0.1:${web.port}$path").build())

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

    @Test fun theItemsOwnDownloadIsSentWithTheTokenAndTheCustomHeaders() {
        runBlocking { store.upsertServer(Server(S1, "Den", "12.4.0", address(), null, true, null, null, mapOf("X-Gate" to "open"))) }
        web.enqueue(MockResponse().setBody("bytes"))
        val r = route()
        send(r, Request.Builder().url(r.url(StreamSource("/Items/$item/Download", emptyList(), hls = false))).build()).use {
            assertEquals("bytes", it.body.string())
        }
        val sent = web.takeRequest(3, TimeUnit.SECONDS)!!
        assertEquals("/Items/$item/Download", sent.path)
        assertTrue(sent.getHeader("Authorization")!!.contains("Token=\"tok-1\""))
        assertEquals("open", sent.getHeader("X-Gate"))
    }

    @Test fun onlyThisItemsDownloadPathIsAllowedBesidesVideos() {
        val r = route()
        for (path in listOf(
            "/Items/$other/Download", "/items/$other/download", "/Items/$item/Download/../File", "/Items/$item/File",
            "/Items/$item/Download/x", "/Items/$item", "/Items/${item.uppercase()}/Download", "/Users/$item/Download",
            "/x/Items/$item/Download", "/Items/$item/Download%2F..", "/Items/$item/Downloads",
        )) {
            refused { get(r, path).close() }
        }
    }

    @Test fun theItemsAndDownloadWordsMatchInAnyCase() {
        val r = route()
        web.enqueue(MockResponse())
        get(r, "/items/$item/download").close()
        assertEquals("/items/$item/download", web.takeRequest(3, TimeUnit.SECONDS)!!.path)
    }

    @Test fun postAndOtherMethodsAreRefusedOnTheDownloadPath() {
        val r = route()
        val url = "http://127.0.0.1:${web.port}/Items/$item/Download"
        refused { send(r, Request.Builder().url(url).post("x".toRequestBody()).build()).close() }
        refused { send(r, Request.Builder().url(url).delete().build()).close() }
        web.enqueue(MockResponse())
        send(r, Request.Builder().url(url).head().build()).close()
        assertEquals("HEAD", web.takeRequest(3, TimeUnit.SECONDS)!!.method)
    }

    @Test fun anotherAuthorityIsRefused() {
        val elsewhere = MockWebServer().apply { start() }
        try {
            val r = route()
            refused { send(r, Request.Builder().url(elsewhere.url("/Items/$item/Download")).build()).close() }
            refused { send(r, Request.Builder().url("http://localhost:${web.port}/Items/$item/Download").build()).close() }
            assertEquals(0, elsewhere.requestCount)
        } finally {
            elsewhere.shutdown()
        }
    }

    @Test fun videosStillWorks() {
        val r = route()
        web.enqueue(MockResponse().setBody("seg"))
        send(r, Request.Builder().url(r.url(MediaPaths.directStream(item, "abc123", null))).build()).close()
        assertEquals("/Videos/$item/stream?static=true&mediaSourceId=abc123", web.takeRequest(3, TimeUnit.SECONDS)!!.path)
    }

    @Test fun theBasePathIsKeptAndMustMatch() {
        saveServer(path = "/jellyfin")
        selector.answer = { online("/jellyfin") }
        val r = route()
        web.enqueue(MockResponse())
        send(r, Request.Builder().url(r.url(StreamSource("/Items/$item/Download", emptyList(), hls = false))).build()).close()
        assertEquals("/jellyfin/Items/$item/Download", web.takeRequest(3, TimeUnit.SECONDS)!!.requestUrl!!.encodedPath)
        refused { get(r, "/Items/$item/Download").close() }
        refused { get(r, "/JELLYFIN/Items/$item/Download").close() }
    }

    @Test fun aStaleRouteRefusesAsBefore() {
        val r = route()
        runBlocking { store.removeAccount(S1, app.shoalwatch.U1) }
        refused(AppError.Kind.NOT_SIGNED_IN) { get(r, "/Items/$item/Download").close() }
    }

    @Test fun anItemIdThatIsNotANormalisedGuidMakesNoRoute() {
        for (bad in listOf("../System", "", item.uppercase(), "0123")) {
            try {
                route(id = bad)
                fail("expected a refusal")
            } catch (e: AppError) {
                assertEquals(AppError.Kind.SERVER_NOT_FOUND, e.kind)
            }
        }
        assertEquals(0, web.requestCount)
    }

    @Test fun thePinnedSelfSignedServerIsReachedThroughThePin() {
        val cert = HeldCertificate.Builder().commonName("lan").addSubjectAlternativeName("127.0.0.1").build()
        val tls = MockWebServer().apply {
            useHttps(HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(), false)
            start()
        }
        try {
            val https = ServerAddress.parse("https://127.0.0.1:${tls.port}")
            runBlocking { store.upsertServer(Server(S1, "Den", "12.4.0", https, null, false, spkiSha256Base64(cert.certificate), null, emptyMap())) }
            selector.answer = { app.shoalwatch.data.servers.Connection.Online(https, isHome = true) }
            tls.enqueue(MockResponse().setBody("bytes"))
            val r = route()
            send(r, Request.Builder().url(r.url(StreamSource("/Items/$item/Download", emptyList(), hls = false))).build()).use {
                assertEquals("bytes", it.body.string())
            }
            assertNotNull(tls.takeRequest(3, TimeUnit.SECONDS)!!.getHeader("Authorization"))
        } finally {
            tls.shutdown()
        }
    }

    @Test fun theOrdinaryMediaRouteStillRefusesTheDownloadPath() {
        val r = runBlocking { api.mediaRoute(session) }
        refused { get(r, "/Items/$item/Download").close() }
    }
}
