package app.shoalwatch.data.playback.engine

import android.content.Context
import android.graphics.Color
import android.view.View
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import app.shoalwatch.data.playback.SubtitleAppearance
import app.shoalwatch.data.playback.SubtitleBackground
import app.shoalwatch.data.playback.SubtitleEdge

/**
 * Where subtitles are drawn (Media3's `SubtitleView`, kept inside the engine so the screen never names a Media3 type): the
 * screen puts [view] over the video, calls [show] with every `PlaybackController.cues` value and [style] with every
 * `SubtitleSettings.appearance` value, so a change applies at once. Main thread only.
 */
@OptIn(UnstableApi::class)
class SubtitleSurface(context: Context) {
    private val subtitles = SubtitleView(context)
    val view: View get() = subtitles

    fun show(frame: SubtitleFrame) = subtitles.setCues(frame.cues)

    fun style(a: SubtitleAppearance) {
        // ASS colours and positions kept, or replaced by the user's style; the user's size always applies.
        subtitles.setApplyEmbeddedStyles(a.keepFileStyling)
        subtitles.setApplyEmbeddedFontSizes(false)
        subtitles.setStyle(captionStyleOf(a))
        subtitles.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * a.sizePercent / 100f)
        subtitles.setBottomPaddingFraction(a.bottomMarginPercent / 100f)
    }

    internal companion object {
        fun captionStyleOf(a: SubtitleAppearance): CaptionStyleCompat = CaptionStyleCompat(
            a.colour.argb,
            when (a.background) {
                SubtitleBackground.NONE -> Color.TRANSPARENT
                SubtitleBackground.SHADED -> SHADED
                SubtitleBackground.SOLID -> Color.BLACK
            },
            Color.TRANSPARENT,
            when (a.edge) {
                SubtitleEdge.NONE -> CaptionStyleCompat.EDGE_TYPE_NONE
                SubtitleEdge.OUTLINE -> CaptionStyleCompat.EDGE_TYPE_OUTLINE
                SubtitleEdge.DROP_SHADOW -> CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW
            },
            Color.BLACK,
            null,
        )

        /** Black at 60%. */
        private const val SHADED = 0x99000000.toInt()
    }
}
