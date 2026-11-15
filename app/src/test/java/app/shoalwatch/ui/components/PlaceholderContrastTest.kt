package app.shoalwatch.ui.components

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import app.shoalwatch.ui.theme.DarkColours
import app.shoalwatch.ui.theme.LightColours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The placeholder letter is large text: at least 3:1 against every tint a placeholder can have, in both themes. */
class PlaceholderContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val hi = maxOf(a.luminance(), b.luminance()).toDouble()
        val lo = minOf(a.luminance(), b.luminance()).toDouble()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun check(name: String, scheme: ColorScheme) {
        var checked = 0
        for (accent in PlaceholderAccent.entries) for (blend in placeholderBlends()) {
            val background = placeholderColour(scheme, PlaceholderStyle(accent, blend))
            val r = ratio(scheme.onSurfaceVariant, background)
            assertTrue("$name $accent at $blend: $r", r >= 3.0)
            checked++
        }
        assertEquals(PlaceholderAccent.entries.size * 8, checked)
    }

    @Test fun darkThemeLetterIsReadableOnEveryTint() = check("dark", DarkColours)
    @Test fun lightThemeLetterIsReadableOnEveryTint() = check("light", LightColours)

    @Test fun everyBlendThePlaceholderStyleCanProduceIsOneOfTheCheckedOnes() {
        for (i in 0 until 500) {
            val style = placeholderStyle("seed-$i")
            assertTrue("${style.amount}", placeholderBlends().any { kotlin.math.abs(it - style.amount) < 1e-6f })
        }
    }
}
