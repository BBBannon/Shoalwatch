package app.shoalwatch.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Redirects (S6): no client from [HttpClientFactory] follows one, so neither the token nor a custom header (a
 * reverse-proxy secret) can be carried to another host. If a later caller turns redirects back on, the
 * network-level guards still strip both for any authority that is not the server's own.
 */
class RedirectPolicyTest {
    private val origin = MockWebServer().apply { start() }
    private val elsewhere = MockWebServer().apply { start() }
    private val token = "MediaBrowser Token=\"secret\""

    @After fun stop() { origin.shutdown(); elsewhere.shutdown() }

    private val factory = HttpClientFactory(
        okhttp3.tls.HandshakeCertificates.Builder().build().trustManager,
        { error("no client certificates in these tests") },
    )

    private val client: OkHttpClient = factory.clientFor(
        ServerNetConfig(
            authorities = setOf(ServerAddress.parse("http://127.0.0.1:${origin.port}").authority),
            allowCleartext = true,
            pinnedSpkiSha256 = null,
            clientCertAlias = null,
            customHeaders = mapOf("CF-Access-Client-Secret" to "proxy-secret"),
        ) { token },
    )

    private fun redirectTo(code: Int) {
        origin.enqueue(MockResponse().setResponseCode(code).setHeader("Location", "http://127.0.0.1:${elsewhere.port}/next"))
        elsewhere.enqueue(MockResponse().setBody("there"))
    }

    private fun get(c: OkHttpClient) = c.newCall(Request.Builder().url("http://127.0.0.1:${origin.port}/x").build()).execute()

    @Test fun `factory clients never follow redirects`() {
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
    }

    @Test fun `a 302 to another host is returned as is and the other host receives nothing`() {
        redirectTo(302)
        val code = get(client).use { it.code }
        assertEquals(302, code)
        val sent = origin.takeRequest()
        assertEquals("proxy-secret", sent.getHeader("CF-Access-Client-Secret"))
        assertEquals(token, sent.getHeader("Authorization"))
        assertEquals(0, elsewhere.requestCount)
    }

    @Test fun `a 307 with a body is returned as is and the body is not resent`() {
        redirectTo(307)
        val request = Request.Builder().url("http://127.0.0.1:${origin.port}/x")
            .post("{\"Pw\":\"hunter2\"}".toRequestBody("application/json".toMediaType())).build()
        val code = client.newCall(request).execute().use { it.code }
        assertEquals(307, code)
        assertEquals(0, elsewhere.requestCount)
    }

    @Test fun `even a caller that turns redirects on cannot carry the token or custom headers to another host`() {
        redirectTo(302)
        val following = client.newBuilder().followRedirects(true).build()
        val body = get(following).use { it.body.string() }
        assertEquals("there", body)
        val leaked = elsewhere.takeRequest(5, TimeUnit.SECONDS)!!
        assertNull(leaked.getHeader("CF-Access-Client-Secret"))
        assertNull(leaked.getHeader("Authorization"))
    }

    @Test fun `even a caller that turns redirects on does not resend a 307 body elsewhere with secrets`() {
        redirectTo(307)
        val following = client.newBuilder().followRedirects(true).build()
        val request = Request.Builder().url("http://127.0.0.1:${origin.port}/x")
            .post("{}".toRequestBody("application/json".toMediaType())).build()
        following.newCall(request).execute().close()
        val leaked = elsewhere.takeRequest(5, TimeUnit.SECONDS)!!
        assertNull(leaked.getHeader("CF-Access-Client-Secret"))
        assertNull(leaked.getHeader("Authorization"))
    }
}
