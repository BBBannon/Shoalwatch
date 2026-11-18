package app.shoalwatch.security

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private var refusing = false
    private val asked = mutableListOf<String>()

    /** As `LockParts` wires it: the household check, here refusing on demand. */
    private fun prefs() = LockPrefs(file) {
        asked += "grownUp"
        if (refusing) throw HouseholdLockedException()
    }

    @Test fun theDefaultsAreOffOneMinuteAndShown() {
        assertEquals(LockSettings(appLock = false, lockAfter = LockAfter.ONE_MINUTE, hideInRecents = false), prefs().settings.value)
    }

    /** Review I1 of Tasks 7-8: household settings are refused below the screens, as PINs and sharing are. */
    @Test fun everySetterAsksTheHouseholdCheckAndARefusalSavesNothing() = runBlocking {
        val prefs = prefs()
        refusing = true
        for (write in listOf<suspend () -> Unit>({ prefs.setAppLock(true) }, { prefs.setLockAfter(LockAfter.FIFTEEN_MINUTES) }, { prefs.setHideInRecents(true) })) {
            val thrown = runCatching { write() }.exceptionOrNull()
            assertTrue("refused: $thrown", thrown is HouseholdLockedException)
        }
        assertEquals(listOf("grownUp", "grownUp", "grownUp"), asked)
        assertEquals("nothing saved", LockSettings(), prefs().settings.value)
        assertEquals(LockSettings(), prefs.settings.value)
    }

    /** `LockGate`'s rule 5: turning off a lock the phone can no longer keep needs no grown-up. */
    @Test fun turningTheAppLockOffForRuleFiveAsksNobody() = runBlocking {
        val prefs = prefs()
        prefs.setAppLock(true)
        asked.clear()
        refusing = true
        prefs.turnOffAppLock()
        assertFalse(prefs.settings.value.appLock)
        assertEquals(emptyList<String>(), asked)
    }

    @Test fun eachSettingIsKeptAcrossANewInstance() = runBlocking {
        val prefs = prefs()
        prefs.setAppLock(true)
        assertEquals(true, prefs.settings.value.appLock)
        prefs.setLockAfter(LockAfter.FIFTEEN_MINUTES)
        prefs.setHideInRecents(true)
        assertEquals(LockSettings(true, LockAfter.FIFTEEN_MINUTES, true), prefs().settings.value)
        prefs.setAppLock(false)
        prefs.setLockAfter(LockAfter.IMMEDIATELY)
        prefs.setHideInRecents(false)
        assertEquals(LockSettings(false, LockAfter.IMMEDIATELY, false), prefs().settings.value)
    }

    @Test fun anUnknownLockAfterReadsOneMinute() {
        file.edit().putString("lock_after", "TEN_YEARS").commit()
        assertEquals(LockAfter.ONE_MINUTE, prefs().settings.value.lockAfter)
    }

    @Test fun aWrongTypeReadsTheDefault() {
        file.edit().putString("app_lock", "yes").putInt("lock_after", 3).putLong("hide_in_recents", 1L).commit()
        assertEquals(LockSettings(), prefs().settings.value)
    }

    @Test fun theMinutesAreTheBriefsValues() {
        assertEquals(listOf(0L, 60_000L, 300_000L, 900_000L), LockAfter.entries.map { it.ms })
    }
}
