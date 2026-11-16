package app.shoalwatch.ui.components

import app.shoalwatch.testId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtworkPlaceholderTest {
    @Test fun theFirstLetterOfATitleIsUpperCased() {
        assertEquals("A", firstGrapheme("alien"))
        assertEquals("T", firstGrapheme("The Lion King"))
        assertEquals("4", firstGrapheme("42 Days"))
        assertEquals("É", firstGrapheme("élan"))
    }

    @Test fun leadingSpacesAndPunctuationAreSkipped() {
        assertEquals("T", firstGrapheme("  the"))
        assertEquals("D", firstGrapheme("\"Dune\""))
        assertEquals("H", firstGrapheme("¡Hola!"))
        assertEquals("L", firstGrapheme("...(Lost)"))
    }

    @Test fun aWholeGraphemeIsTakenNotJustOneChar() {
        assertEquals("É", firstGrapheme("éclair"))
        assertEquals("𝒜", firstGrapheme("𝒜bc"))
        assertEquals("😀", firstGrapheme("😀 Fun"))
    }

    @Test fun nothingUsableGivesNoLetter() {
        assertEquals("", firstGrapheme(null))
        assertEquals("", firstGrapheme(""))
        assertEquals("", firstGrapheme("   "))
        assertEquals("", firstGrapheme("..."))
    }

    @Test fun theTintIsDeterministicAndSubtle() {
        val id = testId("film")
        assertEquals(placeholderStyle(id), placeholderStyle(id))
        for (i in 0 until 200) {
            val style = placeholderStyle(testId("item$i"))
            assertTrue("${style.amount}", style.amount in 0.08f..0.30f)
        }
    }

    @Test fun neighbouringItemsLookSlightlyDifferent() {
        val styles = (0 until 40).map { placeholderStyle(testId("item$it")) }.toSet()
        assertTrue("only ${styles.size} different styles in 40", styles.size >= 6)
        assertEquals(PlaceholderAccent.entries.toSet(), styles.map { it.accent }.toSet())
    }

    @Test fun anEmptySeedStillGivesAStyle() {
        assertEquals(placeholderStyle(""), placeholderStyle(""))
    }

    @Test fun crossfadeIsShortAndOffWhenAnimationsAreRemoved() {
        assertEquals(150, crossfadeMillis(animationsEnabled = true))
        assertEquals(0, crossfadeMillis(animationsEnabled = false))
    }
}
