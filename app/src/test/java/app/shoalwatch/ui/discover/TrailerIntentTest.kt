package app.shoalwatch.ui.discover

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Milestone 11 Task 8 (S7): an online trailer is only ever handed to Android as `ACTION_VIEW` on the validated address: no
 * extras, no flags (so no permission is granted), no category, no type, no package. The app never fetches it.
 */
@RunWith(RobolectricTestRunner::class)
class TrailerIntentTest {
    @Test fun isAPlainViewIntentWithOnlyTheAddress() {
        val intent = trailerIntent("https://www.youtube.com/watch?v=abc")
        assertNotNull(intent)
        intent!!
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://www.youtube.com/watch?v=abc", intent.data.toString())
        assertNull(intent.extras)
        assertEquals(0, intent.flags)
        assertNull(intent.categories)
        assertNull(intent.type)
        assertNull(intent.`package`)
        assertNull(intent.component)
        assertNull(intent.clipData)
    }

    @Test fun httpIsAllowedToo() {
        assertEquals("http://example.org/t", trailerIntent("http://example.org/t")?.data.toString())
    }

    @Test fun anythingThatIsNotHttpOrHttpsIsNotOpened() {
        listOf("file:///sdcard/x.mp4", "content://media/1", "javascript:alert(1)", "intent://x#Intent;scheme=http;end", "market://details?id=x", "youtube.com/watch", "", "https://")
            .forEach { assertNull("opened $it", trailerIntent(it)) }
    }

    @Test fun anAddressLongerThanTheBoundIsNotOpened() {
        assertNull(trailerIntent("https://example.org/" + "a".repeat(2_100)))
    }
}
