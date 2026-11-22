package app.shoalwatch.ui.photos

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * Pinch and double-tap zoom of one photo, as plain numbers so every rule is tested on the JVM: the scale stays between
 * [MIN_SCALE] and [MAX_SCALE], and the picture can never be dragged so far that an edge leaves the screen.
 */
class PhotoZoom(scale: Float = MIN_SCALE, offset: Offset = Offset.Zero) {
    var scale: Float by mutableFloatStateOf(scale.coerceIn(MIN_SCALE, MAX_SCALE))
        private set
    var offset: Offset by mutableStateOf(offset)
        private set

    val zoomed: Boolean get() = scale > MIN_SCALE

    /** A pinch of [zoom] (a factor) and a drag of [pan], on a picture frame of [frame] pixels. */
    fun transform(zoom: Float, pan: Offset, frame: Size) {
        scale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        offset = clamp(offset + pan, frame)
    }

    /** Double tap: back to 1x when zoomed at all, else to [DOUBLE_TAP_SCALE]. */
    fun toggle() {
        scale = if (zoomed) MIN_SCALE else DOUBLE_TAP_SCALE
        offset = Offset.Zero
    }

    fun reset() {
        scale = MIN_SCALE
        offset = Offset.Zero
    }

    private fun clamp(wanted: Offset, frame: Size): Offset {
        val limitX = frame.width * (scale - 1f) / 2f
        val limitY = frame.height * (scale - 1f) / 2f
        return Offset(wanted.x.coerceIn(-limitX, limitX), wanted.y.coerceIn(-limitY, limitY))
    }

    companion object {
        const val MIN_SCALE = 1f
        const val MAX_SCALE = 4f
        const val DOUBLE_TAP_SCALE = 2f
    }
}
