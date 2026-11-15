package app.shoalwatch.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.clearAndSetSemantics
import java.text.BreakIterator
import java.util.Locale

/** Which brand container colour a placeholder leans towards. */
enum class PlaceholderAccent { PRIMARY, TERTIARY }

/** A placeholder's tint: the surface colour blended [amount] (a fraction) of the way to the [accent] container colour. */
data class PlaceholderStyle(val accent: PlaceholderAccent, val amount: Float)

/**
 * A subtle, repeatable tint from [seed] (the item id), so neighbouring cards differ slightly and the same card never changes.
 * Blends 8% to 29% of the way to one of two brand containers, which keeps the letter on top readable in both themes.
 */
fun placeholderStyle(seed: String): PlaceholderStyle {
    val hash = seed.hashCode()
    val accent = if (hash and 1 == 0) PlaceholderAccent.PRIMARY else PlaceholderAccent.TERTIARY
    return PlaceholderStyle(accent, MIN_BLEND + ((hash ushr 1) % BLEND_STEPS) * BLEND_STEP)
}

private const val MIN_BLEND = 0.08f
private const val BLEND_STEP = 0.03f
private const val BLEND_STEPS = 8

/** The first letter (a whole grapheme, upper-cased) of [title], skipping leading spaces and punctuation; empty if there is none. */
fun firstGrapheme(title: String?): String {
    val text = title ?: return ""
    var start = 0
    while (start < text.length && isSkippable(text.codePointAt(start))) start += Character.charCount(text.codePointAt(start))
    if (start >= text.length) return ""
    val rest = text.substring(start)
    val breaks = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(rest) }
    return rest.substring(0, breaks.next()).uppercase(Locale.ROOT)
}

private fun isSkippable(codePoint: Int): Boolean =
    Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || Character.isISOControl(codePoint) ||
        Character.getType(codePoint).toByte() in PUNCTUATION

private val PUNCTUATION = setOf(
    Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION, Character.END_PUNCTUATION,
    Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION, Character.OTHER_PUNCTUATION,
)

/** The crossfade from placeholder to image: short, and none at all when the system's "remove animations" setting is on. */
fun crossfadeMillis(animationsEnabled: Boolean): Int = if (animationsEnabled) CROSSFADE_MS else 0

private const val CROSSFADE_MS = 150

/**
 * What shows while a picture loads, when it failed, and when there is none: a brand-tinted block with the title's first
 * letter. No broken-image icon, no animation. The letter is hidden from accessibility: the card around it carries the name.
 */
@Composable
internal fun ArtworkPlaceholder(seed: String, title: String?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val style = remember(seed) { placeholderStyle(seed) }
    val accent = if (style.accent == PlaceholderAccent.PRIMARY) scheme.primaryContainer else scheme.tertiaryContainer
    val letter = remember(title) { firstGrapheme(title) }
    Box(modifier.background(lerp(scheme.surfaceVariant, accent, style.amount)).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        if (letter.isNotEmpty()) Text(letter, style = MaterialTheme.typography.headlineMedium, color = scheme.onSurfaceVariant, maxLines = 1)
    }
}
