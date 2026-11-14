package app.offlinefin.net

import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AuthInterceptorTest {
    private val a = MockWebServer().apply { start() }
    private val b = MockWebServer().apply { start() }
    private val token = "MediaBrowser Token=\"secret\""

    @After fun stop() { a.shutdown(); b.shutdown() }

    private fun authority(s: MockWebServer) = ServerAddress.parse("http://${s.hostName}:${s.port}").authority

    private fun interceptor(allowed: Set<String>, header: String? = token) = AuthInterceptor({ allowed }, { header })

    private fun get(client: OkHttpClient, url: HttpUrl) =
        client.newCall(Request.Builder().url(url).build()).execute().close()

    @Test fun `adds Authorization for an allowed authority`() {
        a.enqueue(MockResponse())
        get(OkHttpClient.Builder().addInterceptor(interceptor(setOf(authority(a)))).build(), a.url("/x"))
        assertEquals(token, a.takeRequest().getHeader("Authorization"))
    }

    @Test fun `allowed authority comparison is case-insensitive`() {
        a.enqueue(MockResponse())
        get(OkHttpClient.Builder().addInterceptor(interceptor(setOf(authority(a).uppercase()))).build(), a.url("/x"))
        assertEquals(token, a.takeRequest().getHeader("Authorization"))
    }

    @Test fun `same host on another port gets no header`() {
        b.enqueue(MockResponse())
        get(OkHttpClient.Builder().addInterceptor(interceptor(setOf(authority(a)))).build(), b.url("/x"))
        assertNull(b.takeRequest().getHeader("Authorization"))
    }

    @Test fun `strips a caller-supplied Authorization for a host that is not allowed`() {
        b.enqueue(MockResponse())
        val client = OkHttpClient.Builder().addInterceptor(interceptor(setOf(authority(a)))).build()
        client.newCall(Request.Builder().url(b.url("/")).header("Authorization", "mine").build()).execute().close()
        assertNull(b.takeRequest().getHeader("Authorization"))
    }

    @Test fun `replaces a caller-supplied Authorization for an allowed host`() {
        a.enqueue(MockResponse())
        val client = OkHttpClient.Builder().addInterceptor(interceptor(setOf(authority(a)))).build()
        client.newCall(Request.Builder().url(a.url("/")).header("Authorization", "mine").build()).execute().close()
        assertEquals(token, a.takeRequest().getHeader("Authorization"))
    }

    @Test fun `adds nothing when there is no header`() {
        a.enqueue(MockResponse())
        get(OkHttpClient.Builder().addInterceptor(interceptor(setOf(authority(a)), header = null)).build(), a.url("/"))
        assertNull(a.takeRequest().getHeader("Authorization"))
    }

    @Test fun `removes api_key and ApiKey in any case, keeps other parameters`() {
        a.enqueue(MockResponse())
        a.enqueue(MockResponse())
        val client = OkHttpClient.Builder().addInterceptor(interceptor(setOf(authority(a)))).build()
        get(client, a.url("/x?api_key=1&keep=2&ApiKey=3&APIKEY=4&Api_Key=5"))
        assertEquals("/x?keep=2", a.takeRequest().path)
        // Also stripped when the host is not allowed (no header added).
        val other = OkHttpClient.Builder().addInterceptor(interceptor(emptySet())).build()
        get(other, a.url("/y?ApiKey=9"))
        assertEquals("/y", a.takeRequest().path)
    }

    @Test fun `percent-encoded api key names are removed too`() {
        a.enqueue(MockResponse())
        val client = OkHttpClient.Builder().addInterceptor(interceptor(setOf(authority(a)))).build()
        get(client, a.url("/x?api%5Fkey=1&%41piKey=2&keep=3&flag"))
        assertEquals("/x?keep=3&flag", a.takeRequest().path)
    }

    @Test fun `legacy token headers are removed for hosts that are not allowed`() {
        b.enqueue(MockResponse())
        val client = OkHttpClient.Builder().addInterceptor(interceptor(setOf(authority(a)))).build()
        client.newCall(
            Request.Builder().url(b.url("/"))
                .header("X-Emby-Token", "t").header("X-MediaBrowser-Token", "t").header("X-Emby-Authorization", "t")
                .build(),
        ).execute().close()
        val seen = b.takeRequest()
        assertNull(seen.getHeader("X-Emby-Token"))
        assertNull(seen.getHeader("X-MediaBrowser-Token"))
        assertNull(seen.getHeader("X-Emby-Authorization"))
    }

    // OkHttp itself strips Authorization on cross-origin redirects, so this variant would pass even
    // without our interceptor; the network-interceptor variant below is the real proof.
    @Test fun `token is not sent to a second host after a redirect (application interceptor)`() {
        a.enqueue(MockResponse().setResponseCode(302).setHeader("Location", b.url("/landing").toString()))
        b.enqueue(MockResponse())
        get(OkHttpClient.Builder().addInterceptor(interceptor(setOf(authority(a)))).build(), a.url("/start"))
        assertEquals(token, a.takeRequest().getHeader("Authorization"))
        assertNull(b.takeRequest().getHeader("Authorization"))
    }

    @Test fun `token is not sent to a second host after a redirect (network interceptor)`() {
        a.enqueue(MockResponse().setResponseCode(302).setHeader("Location", b.url("/landing").toString()))
        b.enqueue(MockResponse())
        get(OkHttpClient.Builder().addNetworkInterceptor(interceptor(setOf(authority(a)))).build(), a.url("/start"))
        assertEquals(token, a.takeRequest().getHeader("Authorization"))
        assertNull(b.takeRequest().getHeader("Authorization"))
    }

    @Test fun `a redirect back into an allowed host is authorised by the network interceptor`() {
        b.enqueue(MockResponse().setResponseCode(302).setHeader("Location", a.url("/back").toString()))
        a.enqueue(MockResponse())
        val client = OkHttpClient.Builder().addNetworkInterceptor(interceptor(setOf(authority(a)))).build()
        get(client, b.url("/start"))
        assertNull(b.takeRequest().getHeader("Authorization"))
        assertEquals(token, a.takeRequest().getHeader("Authorization"))
    }
}
