package app.shoalwatch.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CustomHeaderInterceptorTest {
    private val a = MockWebServer().apply { start() }
    private val b = MockWebServer().apply { start() }

    @After fun stop() { a.shutdown(); b.shutdown() }

    private fun authority(s: MockWebServer) = ServerAddress.parse("http://${s.hostName}:${s.port}").authority

    private fun client(headers: Map<String, String>, allowed: Set<String> = setOf(authority(a))) =
        OkHttpClient.Builder().addInterceptor(CustomHeaderInterceptor({ allowed }, { headers })).build()

    private fun get(c: OkHttpClient, s: MockWebServer) =
        c.newCall(Request.Builder().url(s.url("/")).build()).execute().close()

    @Test fun `adds custom headers for an allowed authority only`() {
        a.enqueue(MockResponse())
        b.enqueue(MockResponse())
        val c = client(mapOf("CF-Access-Client-Id" to "id123"))
        get(c, a)
        get(c, b)
        assertEquals("id123", a.takeRequest().getHeader("CF-Access-Client-Id"))
        assertNull(b.takeRequest().getHeader("CF-Access-Client-Id"))
    }

    @Test fun `forbidden names are rejected in any letter case without leaking the value`() {
        for (name in listOf("Authorization", "host", "COOKIE", "Content-Length")) {
            try {
                get(client(mapOf(name to "super-secret-value")), a)
                fail("accepted $name")
            } catch (e: IllegalArgumentException) {
                assertFalse(e.message!!.contains("super-secret-value"))
            }
        }
        assertEquals(0, a.requestCount)
    }

    @Test fun `values with CR or LF are rejected without leaking the value`() {
        for (v in listOf("a\r\nX-Evil: 1", "a\nb", "a\rb")) {
            try {
                get(client(mapOf("X-Ok" to v)), a)
                fail("accepted")
            } catch (e: IllegalArgumentException) {
                assertFalse(e.message!!.contains("X-Evil"))
            }
        }
        assertEquals(0, a.requestCount)
    }

    @Test fun `invalid header names are rejected`() {
        try {
            get(client(mapOf("Bad Name" to "the-value")), a)
            fail("accepted")
        } catch (e: IllegalArgumentException) {
            assertFalse(e.message!!.contains("the-value"))
        }
        assertEquals(0, a.requestCount)
    }

    @Test fun `non-ascii values are rejected with a name-only message`() {
        try {
            get(client(mapOf("X-Ok" to "sécret")), a)
            fail("accepted")
        } catch (e: IllegalArgumentException) {
            assertFalse(e.message!!.contains("cret"))
            assertTrue(e.message!!.contains("X-Ok"))
        }
        assertEquals(0, a.requestCount)
    }

    @Test fun `non-ascii names are rejected without echoing them`() {
        try {
            get(client(mapOf("X-sécretname" to "v")), a)
            fail("accepted")
        } catch (e: IllegalArgumentException) {
            assertFalse(e.message!!.contains("cretname"))
        }
        assertEquals(0, a.requestCount)
    }

    @Test fun `values are not validated for a host that is not allowed`() {
        b.enqueue(MockResponse())
        get(client(mapOf("X-Proxy" to "bad\r\nvalue")), b) // an invalid value, but never inspected for b
        assertNull(b.takeRequest().getHeader("X-Proxy"))
        assertEquals(1, b.requestCount)
    }

    @Test fun `configured header names are removed for a host that is not allowed`() {
        b.enqueue(MockResponse())
        val c = client(mapOf("CF-Access-Client-Secret" to "proxy-secret"))
        // As if an application-level copy had already added it before a redirect to b.
        c.newCall(Request.Builder().url(b.url("/")).header("cf-access-client-secret", "proxy-secret").build()).execute().close()
        assertNull(b.takeRequest().getHeader("CF-Access-Client-Secret"))
    }
}
