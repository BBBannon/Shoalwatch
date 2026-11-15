package app.shoalwatch.ui.present

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain JVM: the overview is shown as text, whatever it contains. */
class HtmlTextTest {
    @Test fun plainTextPassesThrough() = assertEquals("A quiet film.", plainOverview("A quiet film."))

    @Test fun nullAndBlankAreEmpty() {
        assertEquals("", plainOverview(null))
        assertEquals("", plainOverview("   \n "))
    }

    @Test fun lineBreakTagsBecomeLineBreaks() {
        assertEquals("One\nTwo", plainOverview("One<br>Two"))
        assertEquals("One\nTwo", plainOverview("One<br/>Two"))
        assertEquals("One\nTwo", plainOverview("One<BR />Two"))
        assertEquals("One\n\nTwo", plainOverview("<p>One</p><p>Two</p>"))
    }

    @Test fun otherTagsAreRemovedButTheirTextStays() {
        assertEquals("Bold and italic", plainOverview("<b>Bold</b> and <i>italic</i>"))
        assertEquals("Click here", plainOverview("<a href=\"https://evil.example\">Click here</a>"))
    }

    @Test fun scriptIsNeverRunOrKeptAsATag() {
        val out = plainOverview("Hi<script>alert(1)</script>there")
        assertFalse(out.contains("<"))
        assertFalse(out.contains(">"))
        assertEquals("Hialert(1)there", out)
    }

    @Test fun anUnclosedTagDropsTheRest() {
        assertEquals("Before", plainOverview("Before<script src=x"))
        assertEquals("Before", plainOverview("Before <img onerror=alert(1)"))
    }

    @Test fun nestedAndBrokenTagsLeaveNoMarkup() {
        assertEquals("<script>", plainOverview("<<b>script>"))
        assertEquals("ok", plainOverview("<b><i><u>ok</u></i></b>"))
        assertEquals("a", plainOverview("<div><span>a</span></div>"))
    }

    @Test fun aLessThanThatIsNotATagStaysText() {
        assertEquals("1 < 2 and 3 <4", plainOverview("1 < 2 and 3 <4"))
        assertEquals("I <3 films", plainOverview("I <3 films"))
    }

    @Test fun entitiesAreDecoded() {
        assertEquals("Tom & Jerry", plainOverview("Tom &amp; Jerry"))
        assertEquals("a < b > c", plainOverview("a &lt; b &gt; c"))
        assertEquals("say \"hi\" it's", plainOverview("say &quot;hi&quot; it&#39;s"))
        assertEquals("a b", plainOverview("a&nbsp;b"))
        assertEquals("é", plainOverview("&#233;"))
        assertEquals("é", plainOverview("&#xE9;"))
    }

    @Test fun entitiesAreDecodedOnceAndNeverBecomeMarkup() {
        assertEquals("<b>x</b>", plainOverview("&lt;b&gt;x&lt;/b&gt;"))
        assertEquals("&lt;", plainOverview("&amp;lt;"))
        assertEquals("<script>", plainOverview("&lt;script&gt;"))
    }

    @Test fun unknownAndMalformedEntitiesStayAsText() {
        assertEquals("AT&T", plainOverview("AT&T"))
        assertEquals("a &bogus; b", plainOverview("a &bogus; b"))
        assertEquals("&#;", plainOverview("&#;"))
        assertEquals("fish & chips", plainOverview("fish & chips"))
    }

    @Test fun smuggledControlAndBidiCharactersAreDropped() {
        assertEquals("ab", plainOverview("a&#0;b"))
        assertEquals("ab", plainOverview("a&#8238;b"))
        assertEquals("ab", plainOverview("a&#x202E;b"))
        assertEquals("ab", plainOverview("a\u0007b"))
        assertEquals("ab", plainOverview("a‮b"))
        assertEquals("ab", plainOverview("a&#xD800;b"))
        assertEquals("ab", plainOverview("a&#1114112;b"))
    }

    @Test fun blankLinesAreLimitedAndLinesTrimmed() {
        assertEquals("a\n\nb", plainOverview("a\n\n\n\n\nb"))
        assertEquals("a\nb", plainOverview("a   \r\nb"))
    }

    @Test fun longTextIsCutWithAnEllipsis() {
        val out = plainOverview("x".repeat(10_000))
        assertEquals(OVERVIEW_MAX, out.length)
        assertTrue(out.endsWith("…"))
        assertEquals("abc…", plainOverview("abcdefgh", maxLength = 4))
    }

    @Test fun aCutNeverSplitsASurrogatePair() {
        val out = plainOverview("ab😀cd", maxLength = 4)
        assertTrue(out.endsWith("…"))
        assertFalse(out.dropLast(1).lastOrNull()?.let { Character.isHighSurrogate(it) } ?: false)
    }

    @Test fun hugeHostileInputIsBoundedAndFast() {
        val hostile = "<".repeat(200_000) + "&".repeat(200_000) + "<a ".repeat(50_000)
        val out = plainOverview(hostile)
        assertTrue(out.length <= OVERVIEW_MAX)
    }

    @Test fun aRunOfAmpersandsWithNoSemicolonIsReadInLinearTime() {
        val hostile = "&".repeat(3_000_000)
        val start = System.nanoTime()
        val out = plainOverview(hostile)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(out.length <= OVERVIEW_MAX)
        assertTrue("took $ms ms", ms < 300)
    }

    @Test fun anEntityAtTheLongestAllowedLengthStillDecodes() {
        assertEquals("a😀b", plainOverview("a&#x1F600;b"))
        assertEquals("a&b", plainOverview("a&amp;b"))
        assertEquals("a&bcdefghijkl;b", plainOverview("a&bcdefghijkl;b"))
    }
}
