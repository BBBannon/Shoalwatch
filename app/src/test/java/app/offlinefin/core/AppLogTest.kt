package app.offlinefin.core

import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLogTest {
    @Test fun messageIsRedacted() {
        val out = AppLog.format("failed https://a.b/c?api_key=XYZ", null)
        assertFalse(out.contains("XYZ"))
        assertFalse(out.contains("a.b"))
    }

    @Test fun throwableContributesFullClassNameOnly() {
        val out = AppLog.format("oops", UnknownHostException("Unable to resolve host \"jellyfin.example.com\""))
        assertTrue(out.contains("java.net.UnknownHostException"))
        assertFalse(out.contains("jellyfin"))
        assertFalse(out.contains("resolve"))
    }

    @Test fun noThrowableLeavesMessageOnly() = assertEquals("plain", AppLog.format("plain", null))
}
