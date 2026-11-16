package app.shoalwatch.ui

import app.shoalwatch.ui.browse.BEN
import app.shoalwatch.ui.browse.FakeLibrary
import app.shoalwatch.ui.browse.FakePlaystate
import app.shoalwatch.ui.browse.SERVER_ID
import app.shoalwatch.ui.browse.account
import app.shoalwatch.ui.home.HomePhase
import app.shoalwatch.ui.home.HomeViewModel
import app.shoalwatch.ui.nav.StartupState
import app.shoalwatch.ui.nav.StartupViewModel
import app.shoalwatch.ui.servers.ServersViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The saved servers and sign-ins are read from the database while screens watch them; a damaged or full database throws into
 * those reads. That must never end the app, never show what was there before the failure, and Retry must bring it back.
 */
@RunWith(RobolectricTestRunner::class) // AppLog uses android.util.Log
class StoreReadFailureTest {
    @get:Rule val main = MainDispatcherRule()

    private val catalog = FakeCatalog(listOf(testServer(id = SERVER_ID)), listOf(account(BEN, "ben")), account(BEN, "ben")).apply { warmed.complete(Unit) }

    @Test fun homeShowsTheLoadFailedStateNotACrashAndRetryBringsItBack() {
        val v = HomeViewModel(catalog, FakeConnections(), FakeLibrary(), FakePlaystate())
        assertEquals(BEN, v.state.value.session)
        catalog.failReads()
        assertEquals(HomePhase.LOAD_FAILED, v.state.value.phase)
        assertEquals("nothing of the account is kept on show", null, v.state.value.session)
        catalog.healReads()
        v.retry()
        assertEquals(BEN, v.state.value.session)
        assertEquals(HomePhase.READY, v.state.value.phase)
    }

    @Test fun theServersScreenShowsNothingRatherThanCrashing() {
        val v = ServersViewModel(catalog, FakeAuthenticator(catalog))
        assertEquals(1, v.state.value.servers.size)
        catalog.failReads()
        assertTrue(v.state.value.servers.isEmpty())
    }

    @Test fun aFailingReadAtStartIsTheFailureScreenNeverWelcome() {
        catalog.failReads()
        assertEquals(StartupState.Failed, StartupViewModel(catalog).state.value)
    }
}
