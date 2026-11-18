package app.shoalwatch.data.playback

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** "Keep playing in a small window when you leave the app": a device setting in `playback_prefs`, on unless turned off. */
@RunWith(RobolectricTestRunner::class)
class PipPrefsTest {
    private val prefs = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("pip_prefs_test", Context.MODE_PRIVATE)

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun onByDefault() {
        assertTrue(PipPrefs(prefs).autoEnter.value)
    }

    @Test fun aChoiceIsSavedAndReadBack() {
        val pip = PipPrefs(prefs)
        pip.setAutoEnter(false)
        assertFalse("the flow follows at once", pip.autoEnter.value)
        assertFalse("a new reader sees the saved value", PipPrefs(prefs).autoEnter.value)
        pip.setAutoEnter(true)
        assertTrue(PipPrefs(prefs).autoEnter.value)
    }

    @Test fun aValueOfTheWrongTypeReadsAsOn() {
        prefs.edit().putString(PipPrefs.KEY, "no").commit()
        assertTrue(PipPrefs(prefs).autoEnter.value)
    }
}
