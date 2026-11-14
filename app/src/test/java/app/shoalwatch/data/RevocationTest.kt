package app.shoalwatch.data

import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.U2
import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.AuthRepository
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerInfo
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.security.SecretBox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import javax.crypto.KeyGenerator

/**
 * Sign-out and remove server (review I3, I4): a token is sent only to an address verified as this server on the
 * current network (https preferred), never to the raw saved addresses; when none is verified it is sent nowhere,
 * the account or server is removed here anyway, and the caller is told the server was not reached.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class RevocationTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val device = DeviceIdentity("dev-1", "Pixel", "Shoalwatch", "0.1")
    private val gateway = FakeGateway()
    private lateinit var db: AppDatabase
    private lateinit var store: ServerStore
    private lateinit var repo: AuthRepository

    private val home = ServerAddress.parse("http://192.168.1.5:8096")
    private val away = ServerAddress.parse("https://away.example")
    private val server = Server(S1, "Den", "10.10.3", home, away, true, null, null, emptyMap())

    /** By default the selector has verified the home (http) address, as it would on the home Wi-Fi. */
    private val connections = StubConnections { Connection.Online(home, isHome = true) }

    @Before fun open() {
        db = inMemoryDb()
        store = ServerStore(db, SecretBox { key })
        repo = AuthRepository(gateway, store, device, connections)
        kotlinx.coroutines.runBlocking {
            store.upsertServer(server)
            store.upsertAccount(Account(S1, U1, "ben", "tok-1"), makeActive = true)
        }
    }

    @After fun close() = db.close()

    private fun impostorAt(address: ServerAddress) {
        gateway.info = { if (it == address) ServerInfo("impostor", "Den", "10.10.3", it, ServerInfo.Compatibility.SUPPORTED) else ServerInfo(S1, "Den", "10.10.3", it, ServerInfo.Compatibility.SUPPORTED) }
    }

    private fun tokenSentTo(address: ServerAddress) =
        gateway.calls.indices.any { gateway.addresses[it] == address && gateway.headers[it]?.contains("Token=") == true }

    // ---- sign out

    @Test fun signOutRevokesThroughTheVerifiedHttpsAddressThenDeletesLocally() = runTest {
        assertTrue(repo.signOut(S1, U1))
        assertEquals(listOf("logout" to away), gateway.log.filter { it.first == "logout" })
        val logout = gateway.calls.indexOf("logout")
        assertTrue(gateway.headers[logout]!!.endsWith(", Token=\"tok-1\""))
        assertEquals("revoke:$S1:$U1", gateway.nets[logout].authKey)
        assertFalse("nothing token-bearing went over http", tokenSentTo(home))
        assertNull(store.cachedToken(S1, U1))
        assertTrue(store.accounts(S1).first().isEmpty())
        assertNull(store.activeAccount.first())
    }

    @Test fun theIdentityIsCheckedOnTheChosenAddressImmediatelyBeforeTheToken() = runTest {
        repo.signOut(S1, U1)
        val logout = gateway.calls.indexOf("logout")
        assertEquals("info" to away, gateway.log[logout - 1])
        assertFalse(gateway.headers[logout - 1]!!.contains("Token="))
    }

    @Test fun anImpostorOnTheHomeAddressNeverReceivesTheToken() = runTest {
        impostorAt(home)
        gateway.info.let { verify -> gateway.info = { a -> if (a == away) throw AppError.of(AppError.Kind.NETWORK) else verify(a) } }
        assertFalse(repo.signOut(S1, U1))
        assertFalse(tokenSentTo(home))
        assertTrue(gateway.calls.none { it == "logout" })
        assertTrue("removed here regardless", store.accounts(S1).first().isEmpty())
    }

    @Test fun anImpostorOnTheHttpsAddressNeverReceivesTheTokenEither() = runTest {
        impostorAt(away)
        assertTrue(repo.signOut(S1, U1)) // falls back to the verified http address
        assertFalse(tokenSentTo(away))
        assertEquals(listOf("logout" to home), gateway.log.filter { it.first == "logout" })
    }

    @Test fun httpIsUsedOnlyWhenTheHttpsAddressDoesNotAnswer() = runTest {
        gateway.info = { if (it == away) throw AppError.of(AppError.Kind.NETWORK) else ServerInfo(S1, "Den", "10.10.3", it, ServerInfo.Compatibility.SUPPORTED) }
        assertTrue(repo.signOut(S1, U1))
        assertEquals(listOf("logout" to home), gateway.log.filter { it.first == "logout" })
    }

    @Test fun withNoVerifiedAddressTheTokenIsSentNowhereAndTheAccountIsStillRemoved() = runTest {
        connections.online = { null }
        assertFalse(repo.signOut(S1, U1))
        assertTrue(gateway.calls.isEmpty())
        assertTrue(store.accounts(S1).first().isEmpty())
        assertNull(store.cachedToken(S1, U1))
    }

    @Test fun aFailedRevocationIsReportedAndTheAccountIsStillRemoved() = runTest {
        gateway.logout = { throw AppError.of(AppError.Kind.SERVER_ERROR) }
        assertFalse(repo.signOut(S1, U1))
        assertTrue(store.accounts(S1).first().isEmpty())
    }

    @Test fun signOutIsBoundedWhenTheServerNeverAnswers() = runTest {
        gateway.logout = { awaitCancellation() }
        assertFalse(repo.signOut(S1, U1))
        assertEquals(AuthRepository.SIGN_OUT_TIMEOUT_MS, currentTime)
        assertTrue(store.accounts(S1).first().isEmpty())
    }

    @Test fun signOutDoesNotSwallowCancellation() = runTest {
        gateway.logout = { throw CancellationException() }
        try {
            repo.signOut(S1, U1)
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertTrue(store.accounts(S1).first().isEmpty())
    }

    @Test fun signOutOfAnUnknownAccountIsHarmless() = runTest {
        assertTrue(repo.signOut(S1, "ghost"))
        assertTrue(repo.signOut("nope", "ghost"))
        assertTrue(gateway.calls.isEmpty())
    }

    // ---- remove server

    @Test fun removeServerRevokesEverySignInThroughAVerifiedAddressThenDeletes() = runTest {
        store.upsertAccount(Account(S1, U2, "amy", "tok-2"), makeActive = false)
        assertTrue(repo.removeServer(S1))
        val logouts = gateway.calls.indices.filter { gateway.calls[it] == "logout" }
        assertEquals(2, logouts.size)
        assertTrue(logouts.all { gateway.addresses[it] == away })
        assertEquals(setOf("revoke:$S1:$U1", "revoke:$S1:$U2"), logouts.map { gateway.nets[it].authKey }.toSet())
        assertEquals(setOf(true), logouts.map { i -> gateway.headers[i]!!.contains(if (gateway.nets[i].authKey == "revoke:$S1:$U1") "tok-1" else "tok-2") }.toSet())
        assertNull(store.server(S1))
        assertNull(store.cachedToken(S1, U1))
    }

    @Test fun removeServerRevokesInParallelWithinItsOverallLimit() = runTest {
        store.upsertAccount(Account(S1, U2, "amy", "tok-2"), makeActive = false)
        gateway.logout = { awaitCancellation() }
        assertFalse(repo.removeServer(S1))
        assertEquals("both in parallel, one overall limit", AuthRepository.REMOVE_TIMEOUT_MS, currentTime)
        assertEquals(2, gateway.calls.count { it == "logout" })
        assertNull("deleted regardless", store.server(S1))
    }

    @Test fun removeServerWhileOfflineSendsNoTokenAndStillDeletes() = runTest {
        connections.online = { null }
        assertFalse(repo.removeServer(S1))
        assertTrue(gateway.calls.isEmpty())
        assertNull(store.server(S1))
        assertTrue(store.accounts(S1).first().isEmpty())
    }

    @Test fun removeServerWithNoSignInsJustDeletes() = runTest {
        store.removeAccount(S1, U1)
        assertTrue(repo.removeServer(S1))
        assertTrue(gateway.calls.isEmpty())
        assertNull(store.server(S1))
    }
}
