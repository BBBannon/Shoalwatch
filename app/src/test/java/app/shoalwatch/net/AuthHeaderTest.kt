package app.shoalwatch.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthHeaderTest {
    @Test fun `builds MediaBrowser header with token`() {
        assertEquals(
            """MediaBrowser Client="Shoalwatch", Device="Pixel", DeviceId="abc", Version="1.0", Token="tok"""",
            AuthHeader.build("Shoalwatch", "Pixel", "abc", "1.0", "tok"),
        )
    }

    @Test fun `omits token when null`() {
        assertFalse(AuthHeader.build("Shoalwatch", "Pixel", "abc", "1.0", null).contains("Token"))
    }

    @Test fun `device name cannot break out of its quotes`() {
        val h = AuthHeader.build("Shoalwatch", "Ben's \"Phone\", Token=\"evil\\", "abc", "1.0", null)
        // Only the closing quote of each value remains, so the injected text stays inside Device.
        assertEquals(
            """MediaBrowser Client="Shoalwatch", Device="Ben's Phone, Token=evil", DeviceId="abc", Version="1.0"""",
            h,
        )
    }

    @Test fun `quotes backslashes and control characters are removed`() {
        val h = AuthHeader.build("c", "a\"b\\c\r\nd\u0000", "id", "v", null)
        assertEquals("""MediaBrowser Client="c", Device="abcd", DeviceId="id", Version="v"""", h)
    }

    @Test fun `emoji is encoded as one code point and percent is escaped`() {
        val h = AuthHeader.build("c", "P😀 100%", "id", "v", null)
        assertTrue(h, h.contains("""Device="P%F0%9F%98%80%C2%A0100%25""""))
    }

    @Test fun `non-ascii is percent-encoded so OkHttp accepts it`() {
        val h = AuthHeader.build("c", "Bén", "id", "v", null)
        assertTrue(h, h.contains("""Device="B%C3%A9n""""))
        okhttp3.Request.Builder().url("http://x/").header("Authorization", h).build()
    }
}
