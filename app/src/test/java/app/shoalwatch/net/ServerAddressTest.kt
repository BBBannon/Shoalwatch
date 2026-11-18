package app.shoalwatch.net

import app.shoalwatch.core.AppError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ServerAddressTest {
    @Test fun `adds https when no scheme`() {
        val a = ServerAddress.parse("jelly.example")
        assertEquals("https://jelly.example", a.value)
        assertTrue(a.isSecure)
        assertEquals("jelly.example", a.host)
        assertEquals("jelly.example:443", a.authority)
    }

    @Test fun `keeps http and port and drops trailing slash`() {
        val a = ServerAddress.parse("http://192.168.1.5:8096/")
        assertEquals("http://192.168.1.5:8096", a.value)
        assertFalse(a.isSecure)
        assertEquals("192.168.1.5:8096", a.authority)
    }

    @Test fun `trims whitespace and lower-cases scheme and host`() {
        assertEquals("https://jelly.example", ServerAddress.parse("  HTTPS://Jelly.Example  ").value)
    }

    @Test fun `keeps base path without trailing slash`() {
        assertEquals("https://example.org/jellyfin", ServerAddress.parse("https://example.org/jellyfin/").value)
    }

    @Test fun `http default port authority is 80`() {
        assertEquals("host.lan:80", ServerAddress.parse("http://host.lan").authority)
    }

    @Test fun `supports IPv6 literals`() {
        val a = ServerAddress.parse("http://[::1]:8096")
        assertEquals("http://[::1]:8096", a.value)
        assertEquals("[::1]:8096", a.authority)
    }

    @Test fun `toString never reveals the value`() {
        val a = ServerAddress.parse("https://secret.example")
        assertEquals("<server>", a.toString())
        assertFalse("$a".contains("secret"))
    }

    /** Round 4 (1.1.0): an address copied from Jellyfin's web page is accepted; only the server part is kept. */
    @Test fun `an address copied from the browser keeps only the server part`() {
        val cases = mapOf(
            "http://192.0.2.10:8096/web/#/home" to "http://192.0.2.10:8096",
            "http://192.0.2.10:8096/web/index.html#!/home.html" to "http://192.0.2.10:8096",
            "https://example.org/jellyfin/web/index.html#!/home" to "https://example.org/jellyfin",
            "https://example.org/jellyfin/WEB/#/details?id=abc" to "https://example.org/jellyfin",
            "jellyfin.example.org/web/" to "https://jellyfin.example.org",
            "https://example.org/web" to "https://example.org",
            "https://host/#frag" to "https://host",
            "https://host/?a=1" to "https://host",
            "https://host?x" to "https://host",
            "https://example.org/jellyfin?x=1#y" to "https://example.org/jellyfin",
            "https://example.org/website/" to "https://example.org/website", // only a whole "web" segment is the web client
        )
        for ((input, expected) in cases) assertEquals(input, expected, ServerAddress.parse(input).value)
    }

    /** Round 5 (re-review M4): a saved address is read back exactly as it was saved, never cleaned again (a base path of /web stays). */
    @Test fun `a stored address is read back without the browser cleaning`() {
        assertEquals("https://example.org/web", ServerAddress.parse("https://example.org/web", browserCopy = false).value)
        assertEquals("https://example.org", ServerAddress.parse("https://example.org/web").value)
        for (bad in listOf("https://host/#frag", "https://host?x")) {
            try { ServerAddress.parse(bad, browserCopy = false); fail("accepted") } catch (e: AppError) { assertEquals(AppError.Kind.SERVER_NOT_FOUND, e.kind) }
        }
    }

    /** Round 4: the host is always what was typed before the `#` or `?`, never something after it that looks like a host. */
    @Test fun `a fragment or query can never change the host`() {
        val a = ServerAddress.parse("http://evil.example#@192.0.2.10")
        assertEquals("evil.example", a.host)
        val b = ServerAddress.parse("http://192.0.2.10?x@evil.example")
        assertEquals("192.0.2.10", b.host)
        assertEquals("192.0.2.10:80", b.authority)
        val c = ServerAddress.parse("https://example.org#@evil.example/web")
        assertEquals("example.org", c.host)
        assertEquals("https://example.org", c.value)
    }

    @Test fun `rejects bad input with SERVER_NOT_FOUND`() {
        val bad = listOf(
            "", "   ", "ftp://host", "file:///etc/passwd", "https://user:pass@host", "https://user@host",
            "a b.example", "https://", "javascript:alert(1)",
            // Round 4: what is left after dropping a fragment or query must still be a whole, valid address.
            "#/home", "?x=1", "https://#frag", "http://?x@evil.example",
        )
        for (input in bad) {
            try {
                ServerAddress.parse(input)
                fail("accepted: ${input.length} chars")
            } catch (e: AppError) {
                assertEquals(AppError.Kind.SERVER_NOT_FOUND, e.kind)
                assertEquals("That doesn't look like a server address. Check it and try again.", e.userMessage)
            }
        }
    }
}
