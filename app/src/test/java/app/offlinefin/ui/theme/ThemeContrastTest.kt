package app.offlinefin.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG contrast of the brand colours: text at least 4.5:1, large text, icons and borders at least 3:1. */
class ThemeContrastTest {
    private fun ratio(a: Color, b: Color): Double {
        val hi = maxOf(a.luminance(), b.luminance()).toDouble()
        val lo = minOf(a.luminance(), b.luminance()).toDouble()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun check(name: String, s: ColorScheme) {
        val text = listOf(
            "onBackground/background" to (s.onBackground to s.background),
            "onSurface/surfaceContainerHigh" to (s.onSurface to s.surfaceContainerHigh),
            "onSurface/surfaceContainerHighest" to (s.onSurface to s.surfaceContainerHighest),
            "onSurfaceVariant/background" to (s.onSurfaceVariant to s.background),
            "onSurfaceVariant/surfaceContainerHigh" to (s.onSurfaceVariant to s.surfaceContainerHigh),
            "primary/background" to (s.primary to s.background),
            "primary/surfaceContainerHigh" to (s.primary to s.surfaceContainerHigh),
            "onPrimary/primary" to (s.onPrimary to s.primary),
            "onPrimaryContainer/primaryContainer" to (s.onPrimaryContainer to s.primaryContainer),
            "onSecondaryContainer/secondaryContainer" to (s.onSecondaryContainer to s.secondaryContainer),
            "onTertiaryContainer/tertiaryContainer" to (s.onTertiaryContainer to s.tertiaryContainer),
            "onTertiary/tertiary" to (s.onTertiary to s.tertiary),
            "onErrorContainer/errorContainer" to (s.onErrorContainer to s.errorContainer),
            "error/background" to (s.error to s.background),
            "error/surfaceContainerHigh" to (s.error to s.surfaceContainerHigh),
        )
        text.forEach { (label, pair) -> assertTrue("$name $label ${ratio(pair.first, pair.second)}", ratio(pair.first, pair.second) >= 4.5) }
        val graphics = listOf(
            "tertiary/background" to (s.tertiary to s.background),
            "outline/background" to (s.outline to s.background),
        )
        graphics.forEach { (label, pair) -> assertTrue("$name $label ${ratio(pair.first, pair.second)}", ratio(pair.first, pair.second) >= 3.0) }
    }

    @Test fun darkSchemeMeetsContrast() = check("dark", DarkColours)
    @Test fun lightSchemeMeetsContrast() = check("light", LightColours)
}
