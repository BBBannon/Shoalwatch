package app.shoalwatch.data

import app.cash.turbine.test
import app.shoalwatch.core.AppError
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.db.ServerEntity
import app.shoalwatch.data.servers.Account
import app.shoalwatch.data.servers.AuthRepository
import app.shoalwatch.data.servers.AuthResult
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.DeviceIdentity
import app.shoalwatch.data.servers.QuickConnectState
import app.shoalwatch.data.servers.Server
import app.shoalwatch.data.servers.ServerInfo
import app.shoalwatch.data.servers.ServerNetDraft
import app.shoalwatch.data.servers.ServerStore
import app.shoalwatch.data.servers.SignInRoute
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.security.SecretBox
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
import javax.crypto.KeyGenerator

/** Finding a server, Quick Connect and password sign-in. Sign-out and removal are in [RevocationTest]; address choice in [SignInRouteTest]. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AuthRepositoryTest {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val box = SecretBox { key }
    private val device = DeviceIdentity("dev-1", "Pixel", "Shoalwatch", "0.1")
    private val gateway = FakeGateway()
    private lateinit var db: AppDatabase
    private lateinit var store: ServerStore
    private lateinit var repo: AuthRepository

    private val home = ServerAddress.parse("https://home.example")
    private val away = ServerAddress.parse("https://away.example")
    private val server = Server("s1", "Den", "10.10.3", home, away, false, null, null, emptyMap())
    private val connections = StubConnections { Connection.Online(home, isHome = true) }

    @Before fun open() {
        db = inMemoryDb()
        store = ServerStore(db, box)
        repo = AuthRepository(gateway, store, device, connections)
        kotlinx.coroutines.runBlocking { store.upsertServer(server) }
    }

    @After fun close() = db.close()

    private fun info(a: ServerAddress) = ServerInfo("s1", "Den", "10.10.3", a, ServerInfo.Compatibility.SUPPORTED)

    private fun failure(kind: AppError.Kind) = AppError.of(kind)

    private suspend fun route(): SignInRoute = repo.signInRoute("s1")!!.also { gateway.calls.clear(); gateway.addresses.clear(); gateway.nets.clear() }

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
        val states = repo.quickConnect(route()).toList()

        val account = Account("s1", "u1", "ben", "tok-1")
        assertEquals(
            listOf(QuickConnectState.Starting, QuickConnectState.AwaitingApproval("123456"), QuickConnectState.Authenticating, QuickConnectState.Success(account)),
            states,
        )
        assertEquals(8_000, currentTime) // polls at 0, 4 s and 8 s
        assertEquals(listOf("info", "enabled", "initiate", "state", "state", "state", "authQc"), gateway.calls)
        assertEquals(account, store.activeAccount.first())
        assertEquals(setOf("home.example:443", "away.example:443"), gateway.nets.first().authorities)
        assertTrue(gateway.addresses.all { it == home })
    }

    @Test fun quickConnectDisabledFailsWithoutInitiating() = runTest {
        gateway.enabled = { false }
        val states = repo.quickConnect(route()).toList()
        assertEquals(QuickConnectState.Starting, states[0])
        assertEquals(AppError.Kind.QUICK_CONNECT_DISABLED, (states.last() as QuickConnectState.Failed).error.kind)
        assertFalse("initiate" in gateway.calls)
    }

    @Test fun quickConnectTimesOutAfterFiveMinutes() = runTest {
        gateway.polls = { false }
        val states = repo.quickConnect(route()).toList()
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
        val states = repo.quickConnect(route()).toList()
        assertTrue(states.last() is QuickConnectState.Success)
        assertEquals(4, gateway.pollCount)
    }

    @Test fun quickConnectFailsFastOnOtherPollingErrors() = runTest {
        gateway.polls = { throw failure(AppError.Kind.TLS_PIN_CHANGED) }
        val states = repo.quickConnect(route()).toList()
        assertEquals(AppError.Kind.TLS_PIN_CHANGED, (states.last() as QuickConnectState.Failed).error.kind)
        assertEquals(1, gateway.pollCount)
    }

    @Test fun quickConnectMapsUnexpectedExceptionsToAnAppError() = runTest {
        gateway.auth = { throw IllegalStateException("boom with tok-secret") }
        val states = repo.quickConnect(route()).toList()
        val error = (states.last() as QuickConnectState.Failed).error
        assertEquals(AppError.Kind.UNKNOWN, error.kind)
        assertFalse(error.userMessage.contains("tok-secret"))
    }

    @Test fun cancellingCollectionStopsPolling() = runTest {
        gateway.polls = { false }
        repo.quickConnect(route()).test {
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

    @Test fun aRouteThatAuthRepositoryDidNotIssueIsRefused() = runTest {
        val forged = object : SignInRoute {
            override val serverId = "s1"
            override val encrypted = true
        }
        try {
            repo.quickConnect(forged)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        try {
            repo.signInWithPassword(forged, "ben", "pw".toCharArray())
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(gateway.calls.isEmpty())
    }

    @Test fun quickConnectOnAServerWithADamagedPinFailsUntrusted() = runTest {
        val r = route()
        // A pin damaged in the database itself (the store refuses to write a malformed one).
        db.serverDao().upsert(ServerEntity("s1", "Den", "10.10.3", home.value, away.value, false, "not-a-pin", null, box.seal("{}")))
        val states = repo.quickConnect(r).toList()
        assertEquals(AppError.Kind.TLS_UNTRUSTED, (states.last() as QuickConnectState.Failed).error.kind)
        assertTrue(gateway.calls.isEmpty())
    }

    // ---- password

    @Test fun passwordSignInSavesAccountTrimsUsernameAndClearsThePassword() = runTest {
        val password = "hunter2".toCharArray()
        val account = repo.signInWithPassword(route(), "  ben ", password)

        assertEquals(Account("s1", "u1", "ben", "tok-1"), account)
        assertEquals(account, store.activeAccount.first())
        assertEquals("ben", gateway.lastUsername)
        assertEquals("hunter2", gateway.lastPassword)
        assertTrue(password.all { it == '\u0000' })
        assertFalse("Token=" in gateway.nets.last().authHeader()!!)
    }

    @Test fun wrongPasswordIsUnauthorisedNothingSavedAndPasswordStillCleared() {
        gateway.auth = { throw failure(AppError.Kind.UNAUTHORISED) }
        val password = "wrong".toCharArray()
        val e = appError { repo.signInWithPassword(route(), "ben", password) }
        assertEquals(AppError.Kind.UNAUTHORISED, e.kind)
        assertTrue(password.all { it == '\u0000' })
        assertNull(kotlinx.coroutines.runBlocking { store.active() })
    }

    // ---- identity

    private fun otherServerInfo(a: ServerAddress) = ServerInfo("other-server", "Other", "10.10.3", a, ServerInfo.Compatibility.SUPPORTED)

    @Test fun passwordSignInChecksServerIdentityBeforeSendingCredentials() = runTest {
        val r = route()
        gateway.info = ::otherServerInfo
        val password = "hunter2".toCharArray()
        val e = try { repo.signInWithPassword(r, "ben", password); null } catch (e: AppError) { e }
        assertEquals(AppError.Kind.SERVER_MISMATCH, e!!.kind)
        assertEquals(listOf("info"), gateway.calls)
        assertNull(gateway.lastPassword)
        assertTrue(password.all { it == '\u0000' })
        assertNull(store.active())
    }

    @Test fun passwordSignInRejectsAResultFromADifferentServer() = runTest {
        gateway.auth = { AuthResult("u1", "ben", "tok-1", "other-server") }
        val e = try { repo.signInWithPassword(route(), "ben", "pw".toCharArray()); null } catch (e: AppError) { e }
        assertEquals(AppError.Kind.SERVER_MISMATCH, e!!.kind)
        assertTrue(store.accounts("s1").first().isEmpty())
        assertNull(store.active())
    }

    @Test fun anEmptyServerIdIsRejected() = runTest {
        gateway.auth = { AuthResult("u1", "ben", "tok-1", "") }
        val e = try { repo.signInWithPassword(route(), "ben", "pw".toCharArray()); null } catch (e: AppError) { e }
        assertEquals(AppError.Kind.SERVER_MISMATCH, e!!.kind)
        gateway.auth = { AuthResult("u1", "ben", "tok-1", "s1") }
        val r = route()
        gateway.info = { ServerInfo("", "x", "1", it, ServerInfo.Compatibility.UNKNOWN) }
        val e2 = try { repo.signInWithPassword(r, "ben", "pw".toCharArray()); null } catch (e: AppError) { e }
        assertEquals(AppError.Kind.SERVER_MISMATCH, e2!!.kind)
    }

    @Test fun quickConnectChecksServerIdentityBeforeStartingAndOnResult() = runTest {
        val r = route()
        gateway.info = ::otherServerInfo
        val first = repo.quickConnect(r).toList()
        assertEquals(AppError.Kind.SERVER_MISMATCH, (first.last() as QuickConnectState.Failed).error.kind)
        assertEquals(listOf("info"), gateway.calls)

        gateway.info = ::info
        gateway.auth = { AuthResult("u1", "ben", "tok-1", "other-server") }
        val second = repo.quickConnect(route()).toList()
        assertEquals(AppError.Kind.SERVER_MISMATCH, (second.last() as QuickConnectState.Failed).error.kind)
        assertNull(store.active())
    }

    @Test fun collectorExceptionsPropagateInsteadOfBeingSwallowed() = runTest {
        val seen = mutableListOf<QuickConnectState>()
        try {
            repo.quickConnect(route()).collect {
                seen += it
                if (it is QuickConnectState.AwaitingApproval) throw IllegalStateException("collector failed")
            }
            fail("expected the collector's exception")
        } catch (e: IllegalStateException) {
            assertEquals("collector failed", e.message)
        }
        assertTrue(seen.none { it is QuickConnectState.Failed })
    }

    @Test fun serverRemovedMidSignInIsServerNotFound() = runTest {
        gateway.auth = {
            kotlinx.coroutines.runBlocking { store.removeServer("s1") }
            AuthResult("u1", "ben", "tok-1", "s1")
        }
        val e = try { repo.signInWithPassword(route(), "ben", "pw".toCharArray()); null } catch (e: AppError) { e }
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, e!!.kind)

        store.upsertServer(server)
        val states = repo.quickConnect(route()).toList()
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, (states.last() as QuickConnectState.Failed).error.kind)
    }

    // ---- settings come from the store at the moment of use (review I6)

    @Test fun signInUsesTheSettingsSavedNowNotThoseWhenTheRouteWasIssued() = runTest {
        val r = route()
        val pin = "BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc="
        store.upsertServer(server.copy(pinnedSpkiSha256 = pin, customHeaders = mapOf("X-Proxy" to "new")))
        repo.signInWithPassword(r, "ben", "pw".toCharArray())
        assertTrue(gateway.nets.all { it.pinnedSpkiSha256 == pin && it.customHeaders == mapOf("X-Proxy" to "new") })
    }

    @Test fun aServerRemovedBeforeSignInIsServerNotFoundAndNothingIsSent() = runTest {
        val r = route()
        store.removeServer("s1")
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, appError { repo.signInWithPassword(r, "ben", "pw".toCharArray()) }.kind)
        val states = repo.quickConnect(r).toList()
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, (states.last() as QuickConnectState.Failed).error.kind)
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, appError { repo.signInRoute("s1") }.kind)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test fun aRouteWhoseAddressIsNoLongerSavedIsRefusedAndNothingIsSent() = runTest {
        val r = route() // home
        store.upsertServer(server.copy(homeAddress = null))
        assertEquals(AppError.Kind.SERVER_NOT_FOUND, appError { repo.signInWithPassword(r, "ben", "pw".toCharArray()) }.kind)
        assertTrue(gateway.calls.isEmpty())
    }
}
