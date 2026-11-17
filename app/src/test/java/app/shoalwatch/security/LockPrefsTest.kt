package app.shoalwatch.security

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The app lock settings in `security_prefs` (Milestone 7 Task 4). */
@RunWith(RobolectricTestRunner::class)
class LockPrefsTest {
    private lateinit var file: SharedPreferences

    @Before fun fresh() {
        file = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("security_prefs", Context.MODE_PRIVATE)
        file.edit().clear().commit()
    }

    @Test fun theDefaultsAreOffOneMinuteAndShown() {
        assertEquals(LockSettings(appLock = false, lockAfter = LockAfter.ONE_MINUTE, hideInRecents = false), LockPrefs(file).settings.value)
    }

    @Test fun eachSettingIsKeptAcrossANewInstance() {
        val prefs = LockPrefs(file)
        prefs.setAppLock(true)
        assertEquals(true, prefs.settings.value.appLock)
        prefs.setLockAfter(LockAfter.FIFTEEN_MINUTES)
        prefs.setHideInRecents(true)
        assertEquals(LockSettings(true, LockAfter.FIFTEEN_MINUTES, true), LockPrefs(file).settings.value)
        prefs.setAppLock(false)
        prefs.setLockAfter(LockAfter.IMMEDIATELY)
        prefs.setHideInRecents(false)
        assertEquals(LockSettings(false, LockAfter.IMMEDIATELY, false), LockPrefs(file).settings.value)
    }

    @Test fun anUnknownLockAfterReadsOneMinute() {
        file.edit().putString("lock_after", "TEN_YEARS").commit()
        assertEquals(LockAfter.ONE_MINUTE, LockPrefs(file).settings.value.lockAfter)
    }

    @Test fun aWrongTypeReadsTheDefault() {
        file.edit().putString("app_lock", "yes").putInt("lock_after", 3).putLong("hide_in_recents", 1L).commit()
        assertEquals(LockSettings(), LockPrefs(file).settings.value)
    }

    @Test fun theMinutesAreTheBriefsValues() {
        assertEquals(listOf(0L, 60_000L, 300_000L, 900_000L), LockAfter.entries.map { it.ms })
    }
}
