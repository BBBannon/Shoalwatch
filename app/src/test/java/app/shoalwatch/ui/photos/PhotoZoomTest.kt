package app.shoalwatch.ui.photos

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Milestone 11 Task 10: zoom stays between 1x and 4x, double tap toggles 2x, and the picture cannot leave the screen. */
class PhotoZoomTest {
    private val frame = Size(1000f, 2000f)

    @Test fun startsAtOneTimes() {
        val z = PhotoZoom()
        assertEquals(1f, z.scale, 0f)
        assertFalse(z.zoomed)
    }

    @Test fun pinchingStopsAtFourTimesAndAtOneTimes() {
        val z = PhotoZoom()
        z.transform(10f, Offset.Zero, frame)
        assertEquals(4f, z.scale, 0f)
        z.transform(0.01f, Offset.Zero, frame)
        assertEquals(1f, z.scale, 0f)
    }

    @Test fun doubleTapGoesToTwoThenBack() {
        val z = PhotoZoom()
        z.toggle()
        assertEquals(2f, z.scale, 0f)
        assertTrue(z.zoomed)
        z.toggle()
        assertEquals(1f, z.scale, 0f)
        assertEquals(Offset.Zero, z.offset)
    }

    @Test fun doubleTapWhileZoomedAnyAmountReturnsToOne() {
        val z = PhotoZoom()
        z.transform(3f, Offset.Zero, frame)
        z.toggle()
        assertEquals(1f, z.scale, 0f)
    }

    @Test fun anEdgeCanNeverBeDraggedIntoTheScreen() {
        val z = PhotoZoom()
        z.toggle() // 2x: half a frame of slack each way is 500 x 1000
        z.transform(1f, Offset(9999f, -9999f), frame)
        assertEquals(Offset(500f, -1000f), z.offset)
        z.transform(1f, Offset(-9999f, 9999f), frame)
        assertEquals(Offset(-500f, 1000f), z.offset)
    }

    @Test fun atOneTimesThereIsNowhereToDrag() {
        val z = PhotoZoom()
        z.transform(1f, Offset(300f, 300f), frame)
        assertEquals(Offset.Zero, z.offset)
    }

    @Test fun zoomingBackOutPullsTheOffsetBackInside() {
        val z = PhotoZoom()
        z.transform(4f, Offset(9999f, 0f), frame)
        assertEquals(1500f, z.offset.x, 0f)
        z.transform(0.5f, Offset.Zero, frame) // 2x now: limit is 500
        assertEquals(500f, z.offset.x, 0f)
    }

    @Test fun resetGoesBackToOneTimes() {
        val z = PhotoZoom()
        z.transform(3f, Offset(10f, 10f), frame)
        z.reset()
        assertEquals(1f, z.scale, 0f)
        assertEquals(Offset.Zero, z.offset)
    }
}
