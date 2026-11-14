package app.shoalwatch.core

import org.junit.Assert.assertEquals
import org.junit.Test

/** Server-supplied text is made safe to show (S11, review F6). */
class DisplayTextTest {
    private fun cp(vararg points: Int) = String(points, 0, points.size)

    @Test fun everyFormatCharacterIsStrippedNotJustAHandPickedList() {
        // Soft hyphen, Arabic number sign, Mongolian vowel separator, invisible math operators, interlinear
        // annotation, and a supplementary-plane tag character: all category Cf.
        val noisy = "a" + cp(0x00AD, 0x0600, 0x180E, 0x2061, 0x2062, 0x2063, 0x2064, 0xFFF9, 0xE0001, 0xE0041) + "b"
        assertEquals("ab", displayText(noisy, 80))
        assertEquals("ab", displayText("a" + cp(0x202E, 0x200B, 0x2066, 0xFEFF, 0x061C) + "b", 80))
    }

    @Test fun controlsStillBecomeSpacesAndWhiteSpaceCollapses() {
        assertEquals("a b c", displayText("a\u0007  b\n\tc", 80))
    }

    @Test fun anAllInvisibleStringBecomesTheCallersFallback() {
        assertEquals("Unnamed user", displayText(cp(0x202E, 0x200B, 0x2063) + " ", 80, fallback = "Unnamed user"))
        assertEquals("Unnamed user", displayText(null, 80, fallback = "Unnamed user"))
        assertEquals("", displayText("​", 80))
    }

    @Test fun visibleTextIgnoresTheFallbackAndIsCapped() {
        assertEquals("Den", displayText("Den", 80, fallback = "Unnamed"))
        assertEquals(5, displayText("x".repeat(50), 5, fallback = "Unnamed").length)
    }
}
