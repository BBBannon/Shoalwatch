package app.shoalwatch.data.playback.engine

import androidx.media3.ui.CaptionStyleCompat
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.data.playback.SubtitleAppearance
import app.shoalwatch.data.playback.SubtitleBackground
import app.shoalwatch.data.playback.SubtitleColour
import app.shoalwatch.data.playback.SubtitleEdge
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The user's subtitle appearance as Media3's caption style (what `SubtitleView` draws with). */
@RunWith(RobolectricTestRunner::class)
class SubtitleSurfaceTest {
    private val base = SubtitleAppearance(100, SubtitleColour.WHITE, SubtitleBackground.SHADED, SubtitleEdge.OUTLINE, keepFileStyling = true, bottomMarginPercent = 8)

    @Test fun colourBackgroundAndEdgeMapToTheCaptionStyle() {
        val s = SubtitleSurface.captionStyleOf(base.copy(colour = SubtitleColour.YELLOW, background = SubtitleBackground.SOLID, edge = SubtitleEdge.DROP_SHADOW))
        assertEquals(0xFFFFFF00.toInt(), s.foregroundColor)
        assertEquals(0xFF000000.toInt(), s.backgroundColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, s.edgeType)
        assertEquals(0, s.windowColor)
        assertEquals(0x99000000.toInt(), SubtitleSurface.captionStyleOf(base).backgroundColor)
        assertEquals(0, SubtitleSurface.captionStyleOf(base.copy(background = SubtitleBackground.NONE)).backgroundColor)
        assertEquals(CaptionStyleCompat.EDGE_TYPE_NONE, SubtitleSurface.captionStyleOf(base.copy(edge = SubtitleEdge.NONE)).edgeType)
    }

    @Test fun aSurfaceTakesEveryAppearanceAndFrameWithoutFailing() {
        val surface = SubtitleSurface(ApplicationProvider.getApplicationContext())
        surface.style(base)
        surface.style(base.copy(sizePercent = 300, keepFileStyling = false, bottomMarginPercent = 30))
        surface.show(SubtitleFrame.NONE)
        surface.show(SubtitleFiles.parse("1\n00:00:01,000 --> 00:00:02,000\nHi\n".toByteArray(), "srt").frameAt(1_500))
    }
}
