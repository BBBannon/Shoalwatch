package app.shoalwatch.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.S1
import app.shoalwatch.S2
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.data.downloads.DownloadPlanner
import app.shoalwatch.data.downloads.DownloadPrefs
import app.shoalwatch.data.downloads.DownloadRepository
import app.shoalwatch.data.downloads.DownloadScheduler
import app.shoalwatch.data.downloads.FakeDownloadGateway
import app.shoalwatch.data.downloads.HousekeepingRepository
import app.shoalwatch.data.downloads.ReleaseTestBase
import app.shoalwatch.data.downloads.ReleasedFiles
import app.shoalwatch.data.downloads.SharedDelete
import app.shoalwatch.data.playback.fixtureInfo
import app.shoalwatch.data.servers.AuthRepository
import app.shoalwatch.data.servers.AuthenticatorImpl
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.StoreCatalog
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.security.HouseholdGuard
import app.shoalwatch.security.HouseholdLockedException
import app.shoalwatch.security.PinCheck
import app.shoalwatch.security.PinHasher
import app.shoalwatch.security.ProfileLocks
import app.shoalwatch.security.SecretBox
import app.shoalwatch.testId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.After
import org.junit.Before
import org.junit.Test
import javax.crypto.KeyGenerator

/**
 * The household check enforced below the screens (Milestone 7 Task 3): the real repositories behind each seam refuse a household
 * change while a protected profile exists and an unprotected one is active, unless a protected profile's PIN was just typed.
 * Adult = (S1, U1, protected), kid = (S1, U2, active). The gateway fake records every request, so "nothing sent" is checked.
 */
class HouseholdEnforcementTest : ReleaseTestBase() {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val clock = app.shoalwatch.TestMonotonic()
    private val authGateway = FakeGateway()
    private lateinit var locks: ProfileLocks
    private lateinit var guard: HouseholdGuard
    private lateinit var catalog: StoreCatalog
    private lateinit var auth: AuthenticatorImpl
    private lateinit var downloads: DownloadRepository
    private lateinit var guardedHousekeeping: HousekeepingRepository

    /** The guard's own collectors live here, not in the base's `appScope`, whose children the base joins after each test. */
    private val guardScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val den = ServerAddress.parse("https://den.example")
    private val newId = testId("s3")

    @Before fun setUpGuard() = runBlocking {
        servers.warmTokens()
        servers.setActive(S1, U2)
        locks = ProfileLocks(db.profileDao(), PinHasher(SecretBox { key }, iterations = 1_000), clock, Dispatchers.IO) {}
        guard = HouseholdGuard(locks, StoreCatalog(servers), clock, guardScope) { true }
        catalog = StoreCatalog(servers, { locks.isProtected(it) }, { guard })
        val repository = AuthRepository(authGateway, servers, DeviceIdentity("dev-1", "Pixel", "Shoalwatch", "0.1"), StubConnections { Connection.Online(den, isHome = true) })
        auth = AuthenticatorImpl(repository) { guard }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = DownloadPrefs(context.getSharedPreferences("household-test", 0).also { it.edit().clear().commit() })
        val planner = DownloadPlanner(app.shoalwatch.ui.browse.FakeLibrary(), db.itemDao(), FakeDownloadGateway(), { _, _ -> fixtureInfo("direct-play.json") }, db.downloadDao(), fake.volumes, prefs)
        val scheduler = DownloadScheduler({ error("WorkManager is not used here") }, prefs)
        downloads = DownloadRepository(
            store, db.offlineLibraryDao(), db.itemDao(), planner, scheduler, prefs, fake.volumes, paths, appScope, Dispatchers.Unconfined, household = guard,
        )
        guardedHousekeeping = HousekeepingRepository(
            releases, db.housekeepingDao(), playstate, ReleasedFiles(paths, playing, appScope), { _, _ -> }, { true }, appScope,
            volumes = fake.volumes, remove = { s, id -> store.removeOwner(s, id) }, household = guard,
        )
    }

    @After fun stopGuard() = guardScope.cancel()

    private fun refused(what: String, block: suspend () -> Unit) = runBlocking {
        try {
            block()
            fail("$what was allowed")
        } catch (_: HouseholdLockedException) {
        }
    }

    private suspend fun accountIds() = servers.accounts(S1).first().map { it.userId }.toSet()

    private fun renamed() = Server(S1, "Renamed", "10.10.3", den, null, false, null, null, emptyMap())

    private fun brandNew() = Server(newId, "New", "10.10.3", ServerAddress.parse("https://new.example"), null, false, null, null, emptyMap())

    @Test fun withAKidActiveEveryHouseholdChangeIsRefusedAndNothingChanges() = runBlocking {
        assertTrue(locks.protect(a, "4826".toCharArray()))

        refused("sharing") { downloads.setSharing(b, true) }
        assertFalse(store.sharing(S1))
        refused("delete shared downloads") { guardedHousekeeping.setSharedDelete(b, SharedDelete.EVERYONE) }
        assertNull(db.housekeepingDao().deleteShared(S1))
        refused("editing a saved server") { catalog.upsertServer(renamed()) }
        assertEquals("Den", servers.server(S1)!!.name)
        refused("removing a server") { auth.removeServer(S2) }
        assertNotNull(servers.server(S2))
        refused("signing out the protected adult") { auth.signOut(S1, U1) }
        assertTrue(U1 in accountIds())
        assertTrue("no request was sent", authGateway.calls.isEmpty())

        catalog.upsertServer(brandNew()) // adding a server is not a household change
        assertNotNull(servers.server(newId))
        auth.signOut(S1, U2) // the kid's own, unprotected profile
        assertFalse(U2 in accountIds())
    }

    @Test fun afterAGrownUpsPinEveryChangeIsAllowed() = runBlocking {
        assertTrue(locks.protect(a, "4826".toCharArray()))
        assertEquals(PinCheck.Correct, guard.confirm(a, "4826".toCharArray()))

        downloads.setSharing(b, true)
        assertTrue(store.sharing(S1))
        guardedHousekeeping.setSharedDelete(b, SharedDelete.EVERYONE)
        assertEquals(SharedDelete.EVERYONE, db.housekeepingDao().deleteShared(S1))
        catalog.upsertServer(renamed())
        assertEquals("Renamed", servers.server(S1)!!.name)
        auth.removeServer(S2)
        assertNull(servers.server(S2))
        auth.signOut(S1, U1)
        assertFalse(U1 in accountIds())
    }

    @Test fun withNoProtectedProfileNothingIsAsked() = runBlocking {
        downloads.setSharing(b, true)
        assertTrue(store.sharing(S1))
        guardedHousekeeping.setSharedDelete(b, SharedDelete.EVERYONE)
        assertEquals(SharedDelete.EVERYONE, db.housekeepingDao().deleteShared(S1))
        catalog.upsertServer(renamed())
        assertEquals("Renamed", servers.server(S1)!!.name)
        auth.removeServer(S2)
        assertNull(servers.server(S2))
        auth.signOut(S1, U1)
        assertFalse(U1 in accountIds())
    }
}
