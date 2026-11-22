package app.shoalwatch.ui.nav

import app.shoalwatch.data.playback.StartFrom
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlayerRouteArgsTest {
    @Test fun theRouteCarriesAnOptionalQueueTokenAndVersion() {
        assertEquals("play/x?from=resume", Routes.player("x", StartFrom.RESUME))
        assertEquals("play/x?from=start&queue=abc_-", Routes.player("x", StartFrom.BEGINNING, queue = "abc_-"))
        assertEquals("play/x?from=resume&queue=q&source=s", Routes.player("x", StartFrom.RESUME, "q", "s"))
        assertEquals("play/x?from=resume&source=s", Routes.player("x", StartFrom.RESUME, source = "s"))
    }
}
