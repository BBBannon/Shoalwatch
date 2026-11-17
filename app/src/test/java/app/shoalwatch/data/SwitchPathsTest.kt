package app.shoalwatch.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.shoalwatch.S1
import app.shoalwatch.TestMonotonic
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.AuthRepository
import app.shoalwatch.data.servers.AuthResult
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.ProfileProtectedException
import app.shoalwatch.data.servers.ProfileSwitch
import app.shoalwatch.data.servers.ProvedSwitches
import app.shoalwatch.data.servers.QuickConnectState
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.data.servers.Session
import app.shoalwatch.data.servers.StoreCatalog
import app.shoalwatch.data.servers.SwitchResult
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.security.HouseholdGuard
import app.shoalwatch.security.LockGate
import app.shoalwatch.security.LockPrefs
import app.shoalwatch.security.LockState
import app.shoalwatch.security.PinHasher
import app.shoalwatch.security.ProfileLocks
import app.shoalwatch.security.SecretBox
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.KeyGenerator

/**
 * The one lock rule on every path that can make a profile active (Milestone 7, safeguarding): whenever a protected profile becomes
 * active without its own PIN typed for that switch, the PIN is asked for. Real store, catalog, sign-in repository, switcher and
 * gate; no screen. Adult = (S1, U1, "ann", protected), kid = (S1, U2, "bob").
 */
@RunWith(RobolectricTestRunner::class)
class SwitchPathsTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val box = SecretBox { key }
    private val clock = TestMonotonic()
    private val gateway = FakeGateway()
    private val den = ServerAddress.parse("https://den.example")
    private val adult = Session(S1, U1)
    private val adultLocked = LockState.Profile(adult, "ann")
    private lateinit var db: AppDatabase
    private lateinit var store: ServerStore
    private lateinit var repo: AuthRepository

    @Before fun open() = runBlocking {
        db = inMemoryDb()
        store = ServerStore(db, box)
        repo = AuthRepository(gateway, store, DeviceIdentity("dev-1", "Pixel", "Shoalwatch", "0.1"), StubConnections { Connection.Online(den, isHome = true) })
        store.warmTokens()
        store.upsertServer(Server(S1, "Den", "10.10.3", den, null, false, null, null, emptyMap()))
        store.upsertAccount(Account(S1, U1, "ann", "tok-1"), makeActive = false)
        store.upsertAccount(Account(S1, U2, "bob", "tok-2"), makeActive = true)
        ProfileLocks(db.profileDao(), PinHasher(box, iterations = 1_000), clock, kotlinx.coroutines.Dispatchers.IO).protect(adult, "4826".toCharArray())
        gateway.auth = { AuthResult(U1, "ann", "tok-new", S1) }
    }

    @After fun close() = db.close()

    private class Parts(val gate: LockGate, val catalog: StoreCatalog, val switcher: ProfileSwitch)

    private fun TestScope.parts(s: ServerStore = store): Parts {
        val locks = ProfileLocks(db.profileDao(), PinHasher(box, iterations = 1_000), clock, StandardTestDispatcher(testScheduler))
        val proved = ProvedSwitches(clock)
        val plain = StoreCatalog(s)
        val household = HouseholdGuard(locks, plain, clock, backgroundScope)
        val prefs = LockPrefs(ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("paths", 0).also { it.edit().clear().commit() })
        val gate = LockGate(prefs.settings, locks, plain, proved, household, { true }, clock, backgroundScope) { prefs.setAppLock(false) }
        val switcher = ProfileSwitch(s::setActive, { s.cachedToken(it.serverId, it.userId) != null }, locks, proved)
        return Parts(gate, StoreCatalog(s, { locks.isProtected(it) }, { household }), switcher)
    }

    private suspend fun Parts.started(): Parts {
        gate.start()
        assertEquals(LockState.Open, gate.state.first { it != LockState.Checking })
        return this
    }

    private suspend fun activeUser() = store.active()?.userId

    private suspend fun route() = repo.signInRoute(S1)!!

    @Test fun aScreenThatCallsTheCatalogDirectlyIsRefused() = runTest {
        val p = parts().started()
        try {
            p.catalog.setActive(S1, U1)
            fail("made the protected profile active without its PIN")
        } catch (_: ProfileProtectedException) {
        }
        assertEquals(U2, activeUser())
    }

    @Test fun theSwitcherAsksForThePinAndOnlyThePinSwitches() = runTest {
        val p = parts().started()
        assertEquals(SwitchResult.NeedsPin, p.switcher.switchTo(S1, U1))
        assertEquals(U2, activeUser())
        assertEquals(SwitchResult.Switched, p.switcher.switchWithPin(S1, U1, "4826".toCharArray()))
        assertEquals(U1, activeUser())
        store.activeAccount.first { it?.userId == U1 }
        assertEquals("the PIN was typed for this switch", LockState.Open, p.gate.state.value)
    }

    @Test fun aPasswordSignInAsTheProtectedProfileIsLocked() = runTest {
        val p = parts().started()
        repo.signInWithPassword(route(), "ann", "hunter2".toCharArray())
        assertEquals(U1, activeUser())
        assertEquals(adultLocked, p.gate.state.first { it is LockState.Profile })
    }

    @Test fun aQuickConnectSignInAsTheProtectedProfileIsLocked() = runTest {
        val p = parts().started()
        val states = repo.quickConnect(route()).toList()
        assertTrue(states.last() is QuickConnectState.Success)
        assertEquals(U1, activeUser())
        assertEquals(adultLocked, p.gate.state.first { it is LockState.Profile })
    }

    @Test fun signingInAgainAfterAnExpiredSignInIsLocked() = runTest {
        val p = parts().started()
        p.switcher.switchWithPin(S1, U1, "4826".toCharArray())
        store.activeAccount.first { it?.userId == U1 }
        store.invalidateToken(S1, U1) // the server rejected the adult's sign-in: nobody usable is active
        store.activeAccount.first { it == null }
        repo.signInWithPassword(route(), "ann", "hunter2".toCharArray()) // "Sign in again" on the expired row
        assertEquals(adultLocked, p.gate.state.first { it is LockState.Profile })
    }

    @Test fun aRestartWithTheProtectedProfileActiveIsLocked() = runTest {
        store.setActive(S1, U1)
        val restarted = ServerStore(db, box) // a new process over the same database
        restarted.warmTokens()
        val p = parts(restarted)
        p.gate.start()
        assertEquals(adultLocked, p.gate.state.first { it != LockState.Checking })
    }

    @Test fun signingOutTheActiveKidLeavesNobodyActiveNotTheProtectedProfile() = runTest {
        val p = parts().started()
        repo.signOut(S1, U2)
        assertNull(activeUser())
        store.activeAccount.first { it == null }
        assertEquals(LockState.Open, p.gate.state.value)
        try {
            p.catalog.setActive(S1, U1) // and a screen that then picks the adult is refused
            fail("made the protected profile active without its PIN")
        } catch (_: ProfileProtectedException) {
        }
        assertNull(activeUser())
    }
}
