package app.shoalwatch.data.playback

import android.content.Context
import android.content.SharedPreferences
import android.view.accessibility.CaptioningManager
import android.view.accessibility.CaptioningManager.CaptionStyle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The phone's caption settings (`CaptioningManager`), as defaults. [language]: the caption language, or null; [phoneLanguage]:
 * the phone's own. [colour], [background] and [edge] are set only when the user switched captions on (their caption style
 * is then a choice they made, not the system's preset); null otherwise.
 */
data class CaptionDefaults(
    val enabled: Boolean,
    val language: String?,
    val fontScale: Float,
    val phoneLanguage: String? = null,
    val colour: SubtitleColour? = null,
    val background: SubtitleBackground? = null,
    val edge: SubtitleEdge? = null,
) {
    companion object {
        /** The nearest of the offered colours to [argb]. */
        fun colourOf(argb: Int): SubtitleColour = SubtitleColour.entries.minBy { distance(it.argb, argb) }

        /** By opacity: clear is NONE, nearly opaque SOLID, anything between SHADED. */
        fun backgroundOf(argb: Int): SubtitleBackground {
            val alpha = argb ushr 24
            return when {
                alpha == 0 -> SubtitleBackground.NONE
                alpha >= 0xE0 -> SubtitleBackground.SOLID
                else -> SubtitleBackground.SHADED
            }
        }

        /** Raised and depressed edges read best as a drop shadow; unknown is null. */
        fun edgeOf(type: Int): SubtitleEdge? = when (type) {
            CaptionStyle.EDGE_TYPE_NONE -> SubtitleEdge.NONE
            CaptionStyle.EDGE_TYPE_OUTLINE -> SubtitleEdge.OUTLINE
            CaptionStyle.EDGE_TYPE_DROP_SHADOW, CaptionStyle.EDGE_TYPE_RAISED, CaptionStyle.EDGE_TYPE_DEPRESSED -> SubtitleEdge.DROP_SHADOW
            else -> null
        }

        private fun distance(a: Int, b: Int): Int {
            fun ch(c: Int, shift: Int) = (c shr shift) and 0xFF
            return listOf(16, 8, 0).sumOf { (ch(a, it) - ch(b, it)).let { d -> d * d } }
        }
    }
}

/** Reads the phone's caption settings now (main thread is fine: it is a settings read). */
fun systemCaptionDefaults(context: Context): CaptionDefaults {
    val phone = Locale.getDefault().toLanguageTag()
    val manager = context.getSystemService(CaptioningManager::class.java) ?: return CaptionDefaults(false, null, 1f, phone)
    val style = manager.userStyle.takeIf { manager.isEnabled }
    return CaptionDefaults(
        enabled = manager.isEnabled,
        language = manager.locale?.toLanguageTag(),
        fontScale = manager.fontScale,
        phoneLanguage = phone,
        colour = style?.takeIf { it.hasForegroundColor() }?.let { CaptionDefaults.colourOf(it.foregroundColor) },
        background = style?.takeIf { it.hasBackgroundColor() }?.let { CaptionDefaults.backgroundOf(it.backgroundColor) },
        edge = style?.takeIf { it.hasEdgeType() }?.let { CaptionDefaults.edgeOf(it.edgeType) },
    )
}

/** How text subtitles look. [sizePercent] of the normal size; [bottomMarginPercent] of the picture height. */
data class SubtitleAppearance(
    val sizePercent: Int,
    val colour: SubtitleColour,
    val background: SubtitleBackground,
    val edge: SubtitleEdge,
    /** ASS colours and positions kept (the user's size still applies); off: the user's colour, background and edge replace the file's. */
    val keepFileStyling: Boolean,
    val bottomMarginPercent: Int,
)

enum class SubtitleColour(val argb: Int) { WHITE(0xFFFFFFFF.toInt()), YELLOW(0xFFFFFF00.toInt()), CYAN(0xFF00FFFF.toInt()), GREEN(0xFF00FF00.toInt()) }
enum class SubtitleBackground { NONE, SHADED, SOLID }
enum class SubtitleEdge { NONE, OUTLINE, DROP_SHADOW }

/**
 * Subtitle appearance, a device setting (not a secret, not per user) in a private `SharedPreferences` file (`playback_prefs`).
 * Until the user changes anything in the app, it follows [captions] (the phone's caption settings, re-read by [refresh]);
 * once they have, their choice wins. Delay is not here: it is per playing item and never saved (`PlaybackController`).
 */
class SubtitleSettings(private val prefs: SharedPreferences, private val captions: () -> CaptionDefaults) {
    private val _appearance = MutableStateFlow(load())
    /** Applies at once: the player screen restyles on every value. */
    val appearance: StateFlow<SubtitleAppearance> = _appearance.asStateFlow()

    fun update(a: SubtitleAppearance) {
        val clean = sanitised(a)
        prefs.edit()
            .putBoolean(K_CHOSEN, true).putInt(K_SIZE, clean.sizePercent).putString(K_COLOUR, clean.colour.name)
            .putString(K_BACKGROUND, clean.background.name).putString(K_EDGE, clean.edge.name)
            .putBoolean(K_KEEP, clean.keepFileStyling).putInt(K_MARGIN, clean.bottomMarginPercent)
            .apply()
        _appearance.value = clean
    }

    /** Follows a change in the phone's caption settings, unless the user has chosen in the app. */
    fun refresh() { _appearance.value = load() }

    private fun load(): SubtitleAppearance {
        val d = defaults()
        if (!prefs.getBoolean(K_CHOSEN, false)) return d
        return sanitised(
            SubtitleAppearance(
                sizePercent = prefs.getInt(K_SIZE, d.sizePercent),
                colour = enumOr(prefs.getString(K_COLOUR, null), d.colour),
                background = enumOr(prefs.getString(K_BACKGROUND, null), d.background),
                edge = enumOr(prefs.getString(K_EDGE, null), d.edge),
                keepFileStyling = prefs.getBoolean(K_KEEP, d.keepFileStyling),
                bottomMarginPercent = prefs.getInt(K_MARGIN, d.bottomMarginPercent),
            ),
        )
    }

    private fun defaults(): SubtitleAppearance {
        val c = captions()
        val size = (c.fontScale.takeIf { it.isFinite() && it > 0f } ?: 1f) * 100
        return sanitised(
            SubtitleAppearance(
                sizePercent = Math.round(size), colour = c.colour ?: SubtitleColour.WHITE, background = c.background ?: SubtitleBackground.SHADED,
                edge = c.edge ?: SubtitleEdge.OUTLINE, keepFileStyling = true, bottomMarginPercent = DEFAULT_MARGIN,
            ),
        )
    }

    companion object {
        const val MIN_SIZE = 50
        const val MAX_SIZE = 300
        const val MAX_MARGIN = 30
        const val DEFAULT_MARGIN = 8
        /** Delay limits for [PlaybackController.setSubtitleDelay]: milliseconds, both ways, in steps of [DELAY_STEP_MS]. */
        const val MAX_DELAY_MS = 10_000
        const val DELAY_STEP_MS = 100

        /** Rounded to the nearest step and kept within the limits. */
        fun delayOf(ms: Int): Int = (Math.round(ms / DELAY_STEP_MS.toFloat()) * DELAY_STEP_MS).coerceIn(-MAX_DELAY_MS, MAX_DELAY_MS)

        private fun sanitised(a: SubtitleAppearance) =
            a.copy(sizePercent = a.sizePercent.coerceIn(MIN_SIZE, MAX_SIZE), bottomMarginPercent = a.bottomMarginPercent.coerceIn(0, MAX_MARGIN))

        private inline fun <reified E : Enum<E>> enumOr(name: String?, fallback: E): E = enumValues<E>().firstOrNull { it.name == name } ?: fallback

        private const val K_CHOSEN = "subtitles_chosen"
        private const val K_SIZE = "subtitle_size"
        private const val K_COLOUR = "subtitle_colour"
        private const val K_BACKGROUND = "subtitle_background"
        private const val K_EDGE = "subtitle_edge"
        private const val K_KEEP = "subtitle_keep_file_styling"
        private const val K_MARGIN = "subtitle_bottom_margin"
    }
}
