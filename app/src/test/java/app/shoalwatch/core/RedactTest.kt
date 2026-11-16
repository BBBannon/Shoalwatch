package app.shoalwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RedactTest {
    @Test fun urlWithApiKeyIsRemoved() {
        val out = Redact.text("GET https://a.b/c?api_key=XYZ failed")
        assertFalse(out.contains("XYZ"))
        assertFalse(out.contains("a.b"))
        assertTrue(out.contains("<url>"))
    }

    @Test fun quotedTokenIsRemoved() {
        val out = Redact.text("""header MediaBrowser Token="abc", Client="x"""")
        assertFalse(out.contains("abc"))
    }

    @Test fun authorizationValueIsRemoved() {
        val out = Redact.text("Authorization: MediaBrowser Client=\"Shoalwatch\", Token=\"secret1\"")
        assertFalse(out.contains("secret1"))
        assertFalse(out.contains("Shoalwatch"))
    }

    @Test fun bareApiKeyAndApiKeyParamsAreRemoved() {
        assertFalse(Redact.text("api_key=SECRETA other").contains("SECRETA"))
        assertFalse(Redact.text("ApiKey=SECRETB other").contains("SECRETB"))
        assertTrue(Redact.text("api_key=SECRETA other").contains("other"))
    }

    @Test fun plainTextIsUnchanged() {
        assertEquals("Loaded 12 items", Redact.text("Loaded 12 items"))
    }

    @Test fun urlHelperReplacesUrl() {
        assertEquals("<url>", Redact.url("https://host.example/path?x=1"))
        assertEquals("<url>", Redact.url("http://10.0.0.5:8096/"))
    }

    @Test fun unquotedTokenIsRemoved() {
        val out = Redact.text("Token=abc123 next")
        assertFalse(out.contains("abc123"))
        assertTrue(out.contains("next"))
    }

    @Test fun singleQuotedValuesAreRemoved() {
        assertFalse(Redact.text("Token='sq-secret' x").contains("sq-secret"))
        assertFalse(Redact.text("api_key='sq-key' x").contains("sq-key"))
    }

    @Test fun tokenHeadersAreRemoved() {
        assertFalse(Redact.text("X-Emby-Token: embySecret").contains("embySecret"))
        assertFalse(Redact.text("X-MediaBrowser-Token: mbSecret").contains("mbSecret"))
    }

    @Test fun bearerValueIsRemoved() {
        val out = Redact.text("sent Bearer abc.def-ghi done")
        assertFalse(out.contains("abc.def-ghi"))
        assertTrue(out.contains("done"))
    }

    @Test fun quotedHostnameIsRemoved() {
        val plain = Redact.text("Unable to resolve host \"jellyfin.example.com\": No address")
        assertFalse(plain, plain.contains("jellyfin.example.com"))
        val escaped = Redact.text("Unable to resolve host \\\"jellyfin.example.com\\\"")
        assertFalse(escaped, escaped.contains("jellyfin.example.com"))
    }
}
