package app.offlinefin.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthHeaderTest {
    @Test fun `builds MediaBrowser header with token`() {
        assertEquals(
            """MediaBrowser Client="OfflineFin", Device="Pixel", DeviceId="abc", Version="1.0", Token="tok"""",
            AuthHeader.build("OfflineFin", "Pixel", "abc", "1.0", "tok"),
        )
    }

    @Test fun `omits token when null`() {
        assertFalse(AuthHeader.build("OfflineFin", "Pixel", "abc", "1.0", null).contains("Token"))
    }

    @Test fun `device name cannot break out of its quotes`() {
        val h = AuthHeader.build("OfflineFin", "Ben's \"Phone\", Token=\"evil\\", "abc", "1.0", null)
        // Only the closing quote of each value remains, so the injected text stays inside Device.
        assertEquals(
            """MediaBrowser Client="OfflineFin", Device="Ben's Phone, Token=evil", DeviceId="abc", Version="1.0"""",
            h,
        )
    }

    @Test fun `quotes backslashes and control characters are removed`() {
        val h = AuthHeader.build("c", "a\"b\\c\r\nd\u0000", "id", "v", null)
        assertEquals("""MediaBrowser Client="c", Device="abcd", DeviceId="id", Version="v"""", h)
    }

    @Test fun `non-ascii is percent-encoded so OkHttp accepts it`() {
        val h = AuthHeader.build("c", "Bén", "id", "v", null)
        assertTrue(h, h.contains("""Device="B%C3%A9n""""))
        okhttp3.Request.Builder().url("http://x/").header("Authorization", h).build()
    }
}
