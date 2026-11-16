package app.shoalwatch.data.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The default quality (final review I-2): a saved device setting; with none, 8 Mbps on mobile data, otherwise the best. */
@RunWith(RobolectricTestRunner::class)
class QualityDefaultTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("quality_default_test", Context.MODE_PRIVATE)
    private var metered = false
    private fun default() = QualityDefault(prefs) { metered }

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun withNothingSavedAnUnmeteredNetworkStartsAtTheBest() {
        assertEquals(Quality.MAXIMUM, default().start())
    }

    @Test fun withNothingSavedAMeteredNetworkStartsAtEightMegabits() {
        metered = true
        assertEquals(Quality.MBPS_8, default().start())
    }

    @Test fun aChosenQualityIsSavedAndWinsOnEveryNetwork() {
        default().choose(Quality.MBPS_4)
        assertEquals(Quality.MBPS_4, default().start())
        metered = true
        assertEquals(Quality.MBPS_4, default().start())
        default().choose(Quality.MAXIMUM)
        assertEquals("the best, chosen on mobile data, is kept", Quality.MAXIMUM, default().start())
    }

    @Test fun anUnknownSavedValueIsIgnored() {
        prefs.edit().putString(QualityDefault.KEY, "MBPS_999").commit()
        metered = true
        assertEquals(Quality.MBPS_8, default().start())
    }
}
