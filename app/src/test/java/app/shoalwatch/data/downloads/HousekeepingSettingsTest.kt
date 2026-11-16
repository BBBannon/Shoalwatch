package app.shoalwatch.data.downloads

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The per-profile and per-server watch-and-delete settings: defaults, each write touching only its own column, and the range. */
class HousekeepingSettingsTest : ReleaseTestBase() {
    @Test fun defaultsWithNoRow() = runBlocking {
        assertEquals(ProfileSettings(AfterFinish.ASK, 0), housekeeping.profileSettings(a).first())
        assertEquals(SharedDelete.ANYONE, housekeeping.sharedDelete(a.serverId).first())
        assertEquals(SharedDelete.ANYONE, housekeeping.sharedDelete("not an id").first())
    }

    @Test fun setSharingDoesNotResetDeleteSharedAndSetSharedDeleteDoesNotResetSharing() = runBlocking {
        housekeeping.setSharedDelete(a, SharedDelete.EVERYONE)
        store.setSharing(a.serverId, true)
        assertEquals(SharedDelete.EVERYONE, housekeeping.sharedDelete(a.serverId).first())
        housekeeping.setSharedDelete(a, SharedDelete.ANYONE)
        housekeeping.setSharedDelete(a, SharedDelete.EVERYONE)
        assertTrue("sharing is still on", store.sharing(a.serverId))
        store.setSharing(a.serverId, false)
        assertEquals(SharedDelete.EVERYONE, housekeeping.sharedDelete(a.serverId).first())
    }

    @Test fun eachProfileSettingKeepsTheOtherAndProfilesAreSeparate() = runBlocking {
        housekeeping.setAutoNext(a, 3)
        housekeeping.setAfterFinish(a, AfterFinish.ALWAYS)
        housekeeping.setAutoNext(a, 5)
        assertEquals(ProfileSettings(AfterFinish.ALWAYS, 5), housekeeping.profileSettings(a).first())
        assertEquals(ProfileSettings(), housekeeping.profileSettings(b).first())
    }

    @Test fun anAutoNextCountOutsideTheChoicesIsRefused() = runBlocking {
        for (bad in listOf(-1, 4, 6, 100)) {
            try {
                housekeeping.setAutoNext(a, bad)
                fail("$bad accepted")
            } catch (e: IllegalArgumentException) {
                Unit
            }
        }
        assertEquals(0, housekeeping.profileSettings(a).first().autoNextCount)
    }
}
