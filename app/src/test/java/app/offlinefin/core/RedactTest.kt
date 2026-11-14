package app.offlinefin.core

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
        val out = Redact.text("Authorization: MediaBrowser Client=\"OfflineFin\", Token=\"secret1\"")
        assertFalse(out.contains("secret1"))
        assertFalse(out.contains("OfflineFin"))
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
}
