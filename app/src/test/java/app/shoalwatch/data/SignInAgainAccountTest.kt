package app.shoalwatch.data

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.AuthRepository
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.QuickConnectState
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.data.servers.StoredToken
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.security.SecretBox
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.KeyGenerator

/**
 * "Sign in again as anna": with Quick Connect whoever approves the code decides the account, so the sign-in must come back as
 * anna or change nothing (the 1.2.0 walkthrough found ben quietly made active while anna stayed expired).
 * The fake server always signs in as ben ([U1], "tok-1").
 */
@RunWith(RobolectricTestRunner::class)
class SignInAgainAccountTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val device = DeviceIdentity("dev-1", "Pixel", "Shoalwatch", "0.1")
    private val gateway = FakeGateway()
    private val home = ServerAddress.parse("https://home.example")
    private val server = Server(S1, "Den", "10.10.3", home, null, false, null, null, emptyMap())
    private lateinit var db: AppDatabase
    private lateinit var store: ServerStore
    private lateinit var repo: AuthRepository

    @Before fun open() {
        db = inMemoryDb()
        store = ServerStore(db, SecretBox { key })
        repo = AuthRepository(gateway, store, device, StubConnections { Connection.Online(home, isHome = true) })
        runBlocking {
            store.warmTokens()
            store.upsertServer(server)
            store.upsertAccount(Account(S1, U2, "anna", "tok-anna"), makeActive = true)
            store.invalidateToken(S1, U2) // anna's sign-in expired: nobody is active
        }
    }

    @After fun close() = db.close()

    private fun quickConnectAs(expected: String?) = runBlocking {
        gateway.polls = { true }
        repo.quickConnect(repo.signInRoute(S1, expected)!!).toList().last()
    }

    @Test fun quickConnectApprovedByAnAccountNotOnThePhoneIsRefusedRevokedAndNotAdded() = runTest {
        val last = quickConnectAs(U2)

        assertEquals(AppError.Kind.WRONG_ACCOUNT, (last as QuickConnectState.Failed).error.kind)
        assertEquals(null, store.activeAccount.first())
        assertEquals(StoredToken.Missing, store.token(S1, U1))
        assertTrue("ben's new sign-in was revoked", "logout" in gateway.calls)
    }

    @Test fun quickConnectApprovedByAnotherProfileOnThePhoneKeepsItsFreshSignInButDoesNotSwitch() = runTest {
        store.upsertAccount(Account(S1, U1, "ben", "tok-old"), makeActive = false)

        val last = quickConnectAs(U2)

        assertEquals(AppError.Kind.WRONG_ACCOUNT, (last as QuickConnectState.Failed).error.kind)
        assertEquals("nobody was made active", null, store.activeAccount.first())
        assertEquals("tok-1", (store.token(S1, U1) as StoredToken.Found).token)
        assertFalse("ben's sign-in is kept, not revoked", "logout" in gateway.calls)
    }

    @Test fun quickConnectApprovedByTheRightProfileSignsItBackIn() = runTest {
        gateway.auth = { app.shoalwatch.data.servers.AuthResult(U2, "anna", "tok-new", S1) }

        val last = quickConnectAs(U2)

        assertTrue(last is QuickConnectState.Success)
        assertEquals(AccountSummary(S1, U2, "anna"), store.activeAccount.first())
    }

    @Test fun passwordSignInAsSomeoneElseIsRefusedToo() = runTest {
        try {
            repo.signInWithPassword(repo.signInRoute(S1, U2)!!, "ben", "pw".toCharArray())
            fail("expected WRONG_ACCOUNT")
        } catch (e: AppError) {
            assertEquals(AppError.Kind.WRONG_ACCOUNT, e.kind)
        }
        assertEquals(null, store.activeAccount.first())
    }

    @Test fun anOrdinarySignInAcceptsAnyAccount() = runTest {
        assertTrue(quickConnectAs(null) is QuickConnectState.Success)
        assertEquals(AccountSummary(S1, U1, "ben"), store.activeAccount.first())
    }
}
