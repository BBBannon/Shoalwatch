package app.shoalwatch.ui.downloads

import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import app.shoalwatch.data.downloads.SharedDelete
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.security.HouseholdLockedException
import app.shoalwatch.ui.FakeCatalog
import app.shoalwatch.ui.MainDispatcherRule
import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.FakeDownloads
import app.shoalwatch.ui.browse.FakeHousekeeping
import app.shoalwatch.ui.browse.SERVER_ID
import app.shoalwatch.ui.browse.account
import app.shoalwatch.ui.lock.FakeHousehold
import app.shoalwatch.ui.testServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** "Share downloads between profiles" and "Delete shared downloads" ask a grown-up's PIN first (Milestone 7 Task 6). */
@RunWith(RobolectricTestRunner::class)
class DownloadSettingsGrownUpTest {
    @get:Rule val main = MainDispatcherRule()

    private val adult = AccountSummary(SERVER_ID, "adult", "Anna")
    private val catalog = FakeCatalog(listOf(testServer(id = SERVER_ID)), listOf(account(BEN, "ben"), adult), account(BEN, "ben"))
    private val downloads = FakeDownloads()
    private val housekeeping = FakeHousekeeping()
    private val household = FakeHousehold(ask = true, grownUps = listOf(adult))

    private fun vm() = DownloadSettingsViewModel(catalog, downloads, housekeeping, household)

    private fun changes() = downloads.calls.filter { it.startsWith("sharing") } + housekeeping.calls.filter { it.startsWith("sharedDelete") }

    @Test fun nothingChangesUntilTheGrownUpsPinIsRight() {
        val vm = vm()
        vm.setSharing(true)
        assertEquals(adult, vm.grownUpAsk.value?.chosen)
        assertTrue(changes().isEmpty())
        vm.grownUpPin.setTextAndPlaceCursorAtEnd("1111")
        vm.submitGrownUp()
        assertTrue("a wrong PIN changes nothing", changes().isEmpty())
        vm.grownUpPin.setTextAndPlaceCursorAtEnd("4826")
        vm.submitGrownUp()
        assertNull(vm.grownUpAsk.value)
        assertEquals(listOf("sharing:true"), changes())
        vm.setDeleteShared(SharedDelete.EVERYONE)
        assertTrue("each change asks", vm.grownUpAsk.value != null)
        vm.grownUpPin.setTextAndPlaceCursorAtEnd("4826")
        vm.submitGrownUp()
        assertEquals(listOf("sharing:true", "sharedDelete:ben:EVERYONE"), changes())
    }

    @Test fun dismissChangesNothing() {
        val vm = vm()
        vm.setDeleteShared(SharedDelete.EVERYONE)
        vm.dismissGrownUp()
        vm.grownUpPin.setTextAndPlaceCursorAtEnd("4826")
        vm.submitGrownUp()
        assertTrue(changes().isEmpty())
        assertTrue(household.confirms.isEmpty())
    }

    @Test fun noCheckNeededChangesAtOnce() {
        household.ask.value = false
        val vm = vm()
        vm.setSharing(false)
        vm.setDeleteShared(SharedDelete.EVERYONE)
        assertNull(vm.grownUpAsk.value)
        assertEquals(listOf("sharing:false", "sharedDelete:ben:EVERYONE"), changes())
    }

    @Test fun aRefusalFromBelowTheScreenSaysAGrownUpIsNeeded() {
        household.ask.value = false // the screen thinks no check is needed, the data layer disagrees (a race)
        val refusing = object : app.shoalwatch.data.downloads.Housekeeping by housekeeping {
            override suspend fun setSharedDelete(s: app.shoalwatch.data.servers.Session, value: SharedDelete) = throw HouseholdLockedException()
        }
        val vm = DownloadSettingsViewModel(catalog, downloads, refusing, household)
        vm.setDeleteShared(SharedDelete.EVERYONE)
        assertTrue(vm.state.value.householdRace)
    }
}
