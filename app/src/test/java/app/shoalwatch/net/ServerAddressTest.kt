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

    @Test fun `rejects bad input with SERVER_NOT_FOUND`() {
        val bad = listOf(
            "", "   ", "ftp://host", "file:///etc/passwd", "https://user:pass@host", "https://user@host",
            "https://host/#frag", "https://host/?a=1", "https://host?x", "a b.example", "https://", "javascript:alert(1)",
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
