package app.shoalwatch.data.playback

import android.content.Context
import android.view.accessibility.CaptioningManager.CaptionStyle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Subtitle appearance: the phone's caption settings until the user chooses, then the user's choice, applied at once. */
@RunWith(RobolectricTestRunner::class)
class SubtitleSettingsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("playback_prefs_test", Context.MODE_PRIVATE)
    private var captions = CaptionDefaults(false, null, 1f)
    private fun settings() = SubtitleSettings(prefs) { captions }

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun withNothingChosenTheAppsDefaultsApply() {
        assertEquals(
            SubtitleAppearance(100, SubtitleColour.WHITE, SubtitleBackground.SHADED, SubtitleEdge.OUTLINE, keepFileStyling = true, bottomMarginPercent = 8),
            settings().appearance.value,
        )
    }

    @Test fun thePhonesCaptionSizeAndStyleAreTheDefaults() {
        captions = CaptionDefaults(true, "en", 1.5f, colour = SubtitleColour.YELLOW, background = SubtitleBackground.SOLID, edge = SubtitleEdge.DROP_SHADOW)
        val a = settings().appearance.value
        assertEquals(150, a.sizePercent)
        assertEquals(SubtitleColour.YELLOW, a.colour)
        assertEquals(SubtitleBackground.SOLID, a.background)
        assertEquals(SubtitleEdge.DROP_SHADOW, a.edge)
    }

    @Test fun aChangeInThePhonesSettingsIsFollowedUntilTheUserChooses() {
        val s = settings()
        captions = captions.copy(fontScale = 2f)
        s.refresh()
        assertEquals(200, s.appearance.value.sizePercent)
        s.update(s.appearance.value.copy(colour = SubtitleColour.CYAN, sizePercent = 125))
        captions = captions.copy(fontScale = 0.5f, colour = SubtitleColour.GREEN)
        s.refresh()
        assertEquals(125, s.appearance.value.sizePercent)
        assertEquals(SubtitleColour.CYAN, s.appearance.value.colour)
    }

    @Test fun anUpdateAppliesAtOnceAndIsKeptForTheNextStart() {
        val s = settings()
        val chosen = SubtitleAppearance(75, SubtitleColour.GREEN, SubtitleBackground.NONE, SubtitleEdge.NONE, keepFileStyling = false, bottomMarginPercent = 20)
        s.update(chosen)
        assertEquals(chosen, s.appearance.value)
        captions = CaptionDefaults(true, "fr", 2f, colour = SubtitleColour.YELLOW)
        assertEquals("the user's choice wins over the phone's from then on", chosen, settings().appearance.value)
    }

    @Test fun outOfRangeValuesAreKeptWithinTheLimits() {
        val s = settings()
        s.update(s.appearance.value.copy(sizePercent = 10_000, bottomMarginPercent = -5))
        assertEquals(300, s.appearance.value.sizePercent)
        assertEquals(0, s.appearance.value.bottomMarginPercent)
        captions = captions.copy(fontScale = Float.NaN)
        prefs.edit().clear().commit()
        assertEquals(100, settings().appearance.value.sizePercent)
    }

    @Test fun aDamagedSavedValueFallsBackToTheDefault() {
        settings().update(settings().appearance.value)
        prefs.edit().putString("subtitle_colour", "PURPLE").commit()
        assertEquals(SubtitleColour.WHITE, settings().appearance.value.colour)
    }

    @Test fun captionStylesMapToTheNearestOfferedChoice() {
        assertEquals(SubtitleColour.YELLOW, CaptionDefaults.colourOf(0xFFF0E010.toInt()))
        assertEquals(SubtitleColour.WHITE, CaptionDefaults.colourOf(0xFFDDDDDD.toInt()))
        assertEquals(SubtitleBackground.NONE, CaptionDefaults.backgroundOf(0x00000000))
        assertEquals(SubtitleBackground.SHADED, CaptionDefaults.backgroundOf(0x80000000.toInt()))
        assertEquals(SubtitleBackground.SOLID, CaptionDefaults.backgroundOf(0xFF000000.toInt()))
        assertEquals(SubtitleEdge.DROP_SHADOW, CaptionDefaults.edgeOf(CaptionStyle.EDGE_TYPE_RAISED))
        assertEquals(SubtitleEdge.OUTLINE, CaptionDefaults.edgeOf(CaptionStyle.EDGE_TYPE_OUTLINE))
    }

    @Test fun theDelayIsInStepsOfATenthBothWaysWithinTenSeconds() {
        assertEquals(1_000, SubtitleSettings.delayOf(1_040))
        assertEquals(-1_100, SubtitleSettings.delayOf(-1_060))
        assertEquals(10_000, SubtitleSettings.delayOf(60_000))
        assertEquals(-10_000, SubtitleSettings.delayOf(Int.MIN_VALUE / 2))
    }

    @Test fun thePhonesSettingsAreReadWithoutFailing() {
        val d = systemCaptionDefaults(context)
        assertEquals(false, d.enabled)
        assertEquals(null, d.colour)
    }
}
