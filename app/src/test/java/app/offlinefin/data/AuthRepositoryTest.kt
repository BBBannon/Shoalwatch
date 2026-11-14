package app.offlinefin.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import app.offlinefin.core.AppError
import app.offlinefin.data.db.AppDatabase
import app.offlinefin.data.servers.Account
import app.offlinefin.data.servers.AuthRepository
import app.offlinefin.data.servers.AuthResult
import app.offlinefin.data.servers.DeviceIdentity
import app.offlinefin.data.servers.JellyfinGateway
import app.offlinefin.data.servers.QcSession
import app.offlinefin.data.servers.QuickConnectState
import app.offlinefin.data.servers.Server
import app.offlinefin.data.servers.ServerInfo
import app.offlinefin.data.servers.ServerNetDraft
import app.offlinefin.data.servers.ServerStore
import app.offlinefin.net.ServerAddress
import app.offlinefin.net.ServerNetConfig
import app.offlinefin.security.SecretBox
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
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
import java.util.concurrent.CancellationException
import javax.crypto.KeyGenerator

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AuthRepositoryTest {
    private class FakeGateway : JellyfinGateway {
        val calls = mutableListOf<String>()
        val nets = mutableListOf<ServerNetConfig>()
        val addresses = mutableListOf<ServerAddress>()
        var info: (ServerAddress) -> ServerInfo = { error("publicInfo not scripted") }
        var enabled: () -> Boolean = { true }
        var polls: (Int) -> Boolean = { true }
        var pollCount = 0
        var auth: () -> AuthResult = { AuthResult("u1", "ben", "tok-1", "s1") }
        var logout: () -> Unit = {}
        var lastPassword: String? = null
        var lastUsername: String? = null

        private fun record(name: String, a: ServerAddress, n: ServerNetConfig) { calls += name; addresses += a; nets += n }

        override suspend fun publicInfo(address: ServerAddress, net: ServerNetConfig): ServerInfo { record("info", address, net); return info(address) }
        override suspend fun quickConnectEnabled(address: ServerAddress, net: ServerNetConfig): Boolean { record("enabled", address, net); return enabled() }
        override suspend fun quickConnectInitiate(address: ServerAddress, net: ServerNetConfig): QcSession { record("initiate", address, net); return QcSession("secret", "123456") }
        override suspend fun quickConnectState(address: ServerAddress, net: ServerNetConfig, secret: String): Boolean {
            record("state", address, net); return polls(pollCount++)
        }
        override suspend fun authenticateWithQuickConnect(address: ServerAddress, net: ServerNetConfig, secret: String): AuthResult {
            record("authQc", address, net); return auth()
        }
        override suspend fun authenticateByName(address: ServerAddress, net: ServerNetConfig, username: String, password: String): AuthResult {
            record("authName", address, net); lastUsername = username; lastPassword = password; return auth()
        }
        override suspend fun logout(address: ServerAddress, net: ServerNetConfig) { record("logout", address, net); logout() }
    }

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val device = DeviceIdentity("dev-1", "Pixel", "OfflineFin", "0.1")
    private val gateway = FakeGateway()
    private lateinit var db: AppDatabase
    private lateinit var store: ServerStore
    private lateinit var repo: AuthRepository

    private val home = ServerAddress.parse("https://home.example")
    private val away = ServerAddress.parse("https://away.example")
    private val server = Server("s1", "Den", "10.10.3", home, away, false, null, null, emptyMap())

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        store = ServerStore(db, SecretBox { key })
        repo = AuthRepository(gateway, store, device)
        kotlinx.coroutines.runBlocking { store.upsertServer(server) }
    }

    @After fun close() = db.close()

    private fun info(a: ServerAddress) = ServerInfo("s1", "Den", "10.10.3", a, ServerInfo.Compatibility.SUPPORTED)

    private fun failure(kind: AppError.Kind) = AppError.of(kind)

    private fun appError(block: suspend () -> Unit): AppError {
        try {
            kotlinx.coroutines.runBlocking { block() }
        } catch (e: AppError) {
            return e
        }
        fail("expected AppError")
        error("unreachable")
    }

    // ---- findServer

    @Test fun findServerUsesDraftSecuritySettingsAndAnonymousHeader() = runTest {
        gateway.info = ::info
        val draft = ServerNetDraft(allowCleartext = false, pinnedSpkiSha256 = null, customHeaders = mapOf("X-A" to "b"))
        val found = repo.findServer("  Home.Example:8920 ", draft)

        assertEquals(ServerAddress.parse("https://home.example:8920"), found.address)
        val net = gateway.nets.single()
        assertEquals(setOf("home.example:8920"), net.authorities)
        assertEquals(mapOf("X-A" to "b"), net.customHeaders)
        assertFalse(net.allowCleartext)
        assertFalse("Token=" in net.authHeader()!!)
        assertTrue(net.authHeader()!!.contains("DeviceId=\"dev-1\""))
    }

    @Test fun findServerFallsBackToHttpOnlyWhenCleartextAllowedAndNoSchemeGiven() = runTest {
        gateway.info = { a -> if (a.isSecure) throw failure(AppError.Kind.NETWORK) else info(a) }
        assertFalse(repo.findServer("nas.local:8096", ServerNetDraft(allowCleartext = true)).address.isSecure)

        gateway.calls.clear()
        val e = try { repo.findServer("nas.local:8096", ServerNetDraft()); null } catch (e: AppError) { e }
        assertEquals(AppError.Kind.NETWORK, e!!.kind)
        assertEquals(listOf("info"), gateway.calls)

        gateway.calls.clear()
        try { repo.findServer("https://nas.local:8096", ServerNetDraft(allowCleartext = true)) } catch (_: AppError) {}
        assertEquals(listOf("info"), gateway.calls)
    }

    @Test fun findServerReportsTheFirstFailureWhenEveryCandidateFails() = runTest {
        var n = 0
        gateway.info = { throw failure(if (n++ == 0) AppError.Kind.TLS_UNTRUSTED else AppError.Kind.NETWORK) }
        val e = try { repo.findServer("nas.local", ServerNetDraft(allowCleartext = true)); null } catch (e: AppError) { e }
        assertEquals(AppError.Kind.TLS_UNTRUSTED, e!!.kind)
    }

    @Test fun findServerRejectsNonsenseWithoutCallingTheServer() = runTest {
        val e = try { repo.findServer("javascript:alert(1)", ServerNetDraft()); null } catch (e: AppError) { e }
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, e!!.kind)
        assertTrue(gateway.calls.isEmpty())
    }

    // ---- quick connect

    @Test fun quickConnectHappyPathSavesAndActivatesTheAccount() = runTest {
        gateway.polls = { it >= 2 } // approved on the third poll
        val states = repo.quickConnect(server, home).toList()

        val account = Account("s1", "u1", "ben", "tok-1")
        assertEquals(
            listOf(QuickConnectState.Starting, QuickConnectState.AwaitingApproval("123456"), QuickConnectState.Authenticating, QuickConnectState.Success(account)),
            states,
        )
        assertEquals(8_000, currentTime) // polls at 0, 4 s and 8 s
        assertEquals(listOf("enabled", "initiate", "state", "state", "state", "authQc"), gateway.calls)
        assertEquals(account, store.activeAccount.first())
        assertEquals(setOf("home.example:443", "away.example:443"), gateway.nets.first().authorities)
        assertTrue(gateway.addresses.all { it == home })
    }

    @Test fun quickConnectDisabledFailsWithoutInitiating() = runTest {
        gateway.enabled = { false }
        val states = repo.quickConnect(server, home).toList()
        assertEquals(QuickConnectState.Starting, states[0])
        assertEquals(AppError.Kind.QUICK_CONNECT_DISABLED, (states.last() as QuickConnectState.Failed).error.kind)
        assertFalse("initiate" in gateway.calls)
    }

    @Test fun quickConnectTimesOutAfterFiveMinutes() = runTest {
        gateway.polls = { false }
        val states = repo.quickConnect(server, home).toList()
        assertEquals(QuickConnectState.TimedOut, states.last())
        assertEquals(300_000, currentTime)
        assertNull(store.activeAccount.first())
        assertFalse("authQc" in gateway.calls)
    }

    @Test fun quickConnectToleratesNetworkAndTimeoutBlipsWhilePolling() = runTest {
        gateway.polls = { n ->
            when (n) {
                0 -> throw failure(AppError.Kind.NETWORK)
                1 -> throw failure(AppError.Kind.TIMEOUT)
                2 -> false
                else -> true
            }
        }
        val states = repo.quickConnect(server, home).toList()
        assertTrue(states.last() is QuickConnectState.Success)
        assertEquals(4, gateway.pollCount)
    }

    @Test fun quickConnectFailsFastOnOtherPollingErrors() = runTest {
        gateway.polls = { throw failure(AppError.Kind.TLS_PIN_CHANGED) }
        val states = repo.quickConnect(server, home).toList()
        assertEquals(AppError.Kind.TLS_PIN_CHANGED, (states.last() as QuickConnectState.Failed).error.kind)
        assertEquals(1, gateway.pollCount)
    }

    @Test fun quickConnectMapsUnexpectedExceptionsToAnAppError() = runTest {
        gateway.auth = { throw IllegalStateException("boom with tok-secret") }
        val states = repo.quickConnect(server, home).toList()
        val error = (states.last() as QuickConnectState.Failed).error
        assertEquals(AppError.Kind.UNKNOWN, error.kind)
        assertFalse(error.userMessage.contains("tok-secret"))
    }

    @Test fun cancellingCollectionStopsPolling() = runTest {
        gateway.polls = { false }
        repo.quickConnect(server, home).test {
            assertEquals(QuickConnectState.Starting, awaitItem())
            assertEquals(QuickConnectState.AwaitingApproval("123456"), awaitItem())
            advanceTimeBy(10_000)
            cancelAndIgnoreRemainingEvents()
        }
        val seen = gateway.pollCount
        advanceTimeBy(60_000)
        assertEquals(seen, gateway.pollCount)
        assertNull(store.activeAccount.first())
    }

    @Test fun quickConnectRefusesAnAddressThatIsNotTheServers() {
        try {
            repo.quickConnect(server, ServerAddress.parse("https://evil.example"))
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertFalse(e.message!!.contains("evil"))
        }
    }

    @Test fun quickConnectOnAServerWithADamagedPinFailsUntrusted() = runTest {
        val states = repo.quickConnect(server.copy(pinInvalid = true), home).toList()
        assertEquals(AppError.Kind.TLS_UNTRUSTED, (states.last() as QuickConnectState.Failed).error.kind)
        assertTrue(gateway.calls.isEmpty())
    }

    // ---- password

    @Test fun passwordSignInSavesAccountTrimsUsernameAndClearsThePassword() = runTest {
        val password = "hunter2".toCharArray()
        val account = repo.signInWithPassword(server, home, "  ben ", password)

        assertEquals(Account("s1", "u1", "ben", "tok-1"), account)
        assertEquals(account, store.activeAccount.first())
        assertEquals("ben", gateway.lastUsername)
        assertEquals("hunter2", gateway.lastPassword)
        assertTrue(password.all { it == '\u0000' })
        assertFalse("Token=" in gateway.nets.single().authHeader()!!)
    }

    @Test fun wrongPasswordIsUnauthorisedNothingSavedAndPasswordStillCleared() {
        gateway.auth = { throw failure(AppError.Kind.UNAUTHORISED) }
        val password = "wrong".toCharArray()
        val e = appError { repo.signInWithPassword(server, home, "ben", password) }
        assertEquals(AppError.Kind.UNAUTHORISED, e.kind)
        assertTrue(password.all { it == '\u0000' })
        assertNull(kotlinx.coroutines.runBlocking { store.active() })
    }

    // ---- sign out

    private suspend fun signedIn() = store.upsertAccount(Account("s1", "u1", "ben", "tok-1"), makeActive = true)

    @Test fun signOutRevokesOnTheServerThenDeletesLocally() = runTest {
        signedIn()
        repo.signOut("s1", "u1")
        assertEquals(listOf("logout"), gateway.calls)
        assertEquals(home, gateway.addresses.single())
        assertTrue(gateway.nets.single().authHeader()!!.endsWith(", Token=\"tok-1\""))
        assertTrue(store.accounts("s1").first().isEmpty())
        assertNull(store.activeAccount.first())
    }

    @Test fun signOutStillDeletesLocallyWhenTheServerCallFails() = runTest {
        signedIn()
        gateway.logout = { throw failure(AppError.Kind.NETWORK) }
        repo.signOut("s1", "u1")
        assertTrue(store.accounts("s1").first().isEmpty())
    }

    @Test fun signOutDoesNotSwallowCancellation() = runTest {
        signedIn()
        gateway.logout = { throw CancellationException() }
        try {
            repo.signOut("s1", "u1")
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
    }

    @Test fun signOutOfAnUnknownAccountIsHarmless() = runTest {
        repo.signOut("s1", "ghost")
        repo.signOut("nope", "ghost")
        assertTrue(gateway.calls.isEmpty())
    }
}
