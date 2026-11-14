package app.shoalwatch.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayParagraphsTest {
    @Test fun crlfAndLoneCrBecomeNewlines() {
        assertEquals("a\nb\nc", displayParagraphs("a\r\nb\rc", 100))
    }

    @Test fun runsOfBlankLinesCollapseToOne() {
        assertEquals("a\n\nb", displayParagraphs("a\n\n\n\n \t \n\nb", 100))
    }

    @Test fun leadingAndTrailingBlankLinesAreRemoved() {
        assertEquals("a\n\nb", displayParagraphs("\n\n  a\n\nb \n\n\n", 100))
    }

    @Test fun allBlankInputIsEmpty() {
        assertEquals("", displayParagraphs("", 100))
        assertEquals("", displayParagraphs(null, 100))
        assertEquals("", displayParagraphs(" \r\n\t\n ​‮ ", 100))
    }

    @Test fun controlFormatAndBidiCharactersAreRemovedButTheLineStays() {
        assertEquals("one two\nthree", displayParagraphs("one\u0000\u0007 two​\nthr‮ee⁦", 100))
    }

    @Test fun otherLineSeparatorsBecomeSpacesNotLineBreaks() {
        assertEquals("a b c", displayParagraphs("a\u2028b\u2029c", 100))
    }

    @Test fun htmlIsLeftAloneAsText() {
        assertEquals("<b>Bold</b> & <script>x()</script>\n<p>two</p>", displayParagraphs("<b>Bold</b> & <script>x()</script>\n<p>two</p>", 100))
    }

    @Test fun aVeryLongLineIsCappedByCodePoints() {
        val out = displayParagraphs("x".repeat(5000), 4000)
        assertEquals(4000, out.length)
    }

    @Test fun theCapNeverSplitsASurrogatePair() {
        val pair = "😀" // one code point, two chars
        val out = displayParagraphs("a" + pair.repeat(10), 4)
        assertEquals("a" + pair.repeat(3), out)
        assertEquals(4, out.codePointCount(0, out.length))
        assertTrue(out.last().isLowSurrogate())
    }

    @Test fun theCapAppliesToTheWholeTextAcrossLines() {
        assertEquals("abc\nde", displayParagraphs("abc\nde\nfgh", 6))
    }
}
