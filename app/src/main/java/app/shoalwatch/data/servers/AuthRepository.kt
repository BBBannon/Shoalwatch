package app.shoalwatch.data.servers

import android.database.sqlite.SQLiteConstraintException
import app.shoalwatch.core.AppError
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.net.ServerNetConfig
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CancellationException

/**
 * Finding a server, signing in, signing out and removing a server. Successful sign-ins are saved to
 * [ServerStore] and made active. Time is `delay`/`withTimeout`, so tests run it in virtual time.
 *
 * Every credential (password, Quick Connect secret, access token) goes only to an address chosen HERE, from the
 * server's settings as saved right now (callers pass ids, never a `Server`):
 * - the address must first be verified by [connections] (`awaitOnline`), so nothing goes to an address that has
 *   not answered as this server on the current network;
 * - an https address of the server is preferred whenever it answers as this server, so a credential goes over
 *   plain http only when that is the server's only address or its https address cannot be reached ([SignInRoute.encrypted]
 *   tells the screen);
 * - the server's identity is checked again on the chosen address immediately before the credential is sent;
 * - Quick Connect, which sends its secret repeatedly, stops the moment the selector's connection for the server is no
 *   longer the one the route was issued on (see [quickConnect]).
 *
 * Passwords and Quick Connect secrets are never stored or logged.
 */
class AuthRepository(
    private val gateway: JellyfinGateway,
    private val store: ServerStore,
    private val device: DeviceIdentity,
    private val connections: ServerConnections,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val timeoutMs: Long = TIMEOUT_MS,
    private val verifyTimeoutMs: Long = AddressSelector.AWAY_TIMEOUT_MS,
    private val signOutTimeoutMs: Long = SIGN_OUT_TIMEOUT_MS,
    private val removeTimeoutMs: Long = REMOVE_TIMEOUT_MS,
) {
    /** The only implementation of [SignInRoute]: a route the UI did not get from here is refused. */
    private class IssuedRoute(
        override val serverId: String,
        val address: ServerAddress,
        val basis: Connection.Online,
        /** "Sign in again" for this profile: a sign-in as anyone else is refused (see [save]). */
        val expectedUserId: String?,
    ) : SignInRoute {
        override val encrypted: Boolean get() = address.isSecure
        override fun toString() = "SignInRoute(<redacted>)"
    }

    /**
     * Parses [input] (a bare host gets `https://`) and asks it for `/System/Info/Public`. If no scheme was
     * typed and [draft] allows cleartext, `http://` is tried when `https://` fails.
     *
     * @throws AppError `SERVER_NOT_FOUND` for an unusable address; otherwise the first candidate's failure.
     */
    suspend fun findServer(input: String, draft: ServerNetDraft): ServerInfo {
        val text = input.trim()
        val candidates = buildList {
            add(ServerAddress.parse(text))
            if ("://" !in text && draft.allowCleartext) add(ServerAddress.parse("http://$text"))
        }
        var first: AppError? = null
        for (address in candidates) {
            try {
                return gateway.publicInfo(address, ServerNet.anonymous(listOf(address), draft, device))
            } catch (e: AppError) {
                if (first == null) first = e
            }
        }
        throw first!!
    }

    /**
     * Where a sign-in for [serverId] would go now, or null if no address is verified. Ask again before each attempt.
     *
     * @throws AppError `SERVER_NOT_FOUND` if the server is no longer saved.
     */
    suspend fun signInRoute(serverId: String, expectedUserId: String? = null): SignInRoute? {
        val server = store.server(serverId) ?: throw AppError.of(AppError.Kind.SERVER_NOT_FOUND)
        return credentialAddress(server)?.let { IssuedRoute(server.id, it.address, it.basis, expectedUserId) }
    }

    /**
     * Quick Connect as a cold flow: collecting it starts the attempt, cancelling collection abandons it
     * (and stops polling). First checks the address really is the server (nothing is requested of an
     * impostor), then emits Starting, AwaitingApproval, polls every 4 s for up to 5 minutes, then
     * Authenticating and Success, or ends with TimedOut / Failed. Network and timeout errors while polling
     * are tolerated; anything else fails at once. If the selector's connection for the server changes while
     * waiting (a network change, offline, another address) it ends with `Failed(CONNECTION_CHANGED)` and no secret
     * is sent after that. The flow completes after a terminal state.
     *
     * Only the gateway calls are guarded: an exception thrown by the collector is never swallowed.
     *
     * @throws IllegalArgumentException if [route] did not come from [signInRoute].
     */
    fun quickConnect(route: SignInRoute): Flow<QuickConnectState> {
        val issued = issued(route)
        return flow {
            emit(QuickConnectState.Starting)
            val begun = guard { begin(issued) }.getOrElse { emit(QuickConnectState.Failed(it)); return@flow }
            val (server, net, session) = begun
            emit(QuickConnectState.AwaitingApproval(session.code))

            val approved = guard { awaitApproval(issued, net, session.secret) }.getOrElse { emit(QuickConnectState.Failed(it)); return@flow }
            if (!approved) {
                emit(QuickConnectState.TimedOut)
                return@flow
            }
            emit(QuickConnectState.Authenticating)
            val account = guard {
                ensureConnectionUnchanged(issued) // nothing more is sent once the network is not the one that was verified
                save(server, gateway.authenticateWithQuickConnect(issued.address, net, session.secret), issued.expectedUserId)
            }.getOrElse { emit(QuickConnectState.Failed(it)); return@flow }
            emit(QuickConnectState.Success(account))
        }
    }

    private data class Begun(val server: Server, val net: ServerNetConfig, val session: QcSession)

    /** Verifies the server's identity with its current settings, then asks for a code. */
    private suspend fun begin(route: IssuedRoute): Begun {
        val server = current(route)
        val net = anonymousNet(server)
        verifyServer(server, route.address, net)
        if (!gateway.quickConnectEnabled(route.address, net)) throw AppError.of(AppError.Kind.QUICK_CONNECT_DISABLED)
        return Begun(server, net, gateway.quickConnectInitiate(route.address, net))
    }

    /**
     * Polls until approved (true) or [timeoutMs] passes (false). A watcher on the selector's connection for this
     * server stops the polling at once, even mid-call, when it is no longer the [IssuedRoute.basis] the secret was
     * issued on (network change, offline, or online on another address): `CONNECTION_CHANGED`.
     */
    private suspend fun awaitApproval(route: IssuedRoute, net: ServerNetConfig, secret: String): Boolean =
        withTimeoutOrNull(timeoutMs) {
            coroutineScope {
                val watcher = launch {
                    connections.connection(route.serverId).first { it != route.basis }
                    throw AppError.of(AppError.Kind.CONNECTION_CHANGED)
                }
                ensureConnectionUnchanged(route)
                while (!approved(route.address, net, secret)) delay(pollIntervalMs)
                watcher.cancel()
                true
            }
        } ?: false

    private fun ensureConnectionUnchanged(route: IssuedRoute) {
        if (connections.connection(route.serverId).value != route.basis) throw AppError.of(AppError.Kind.CONNECTION_CHANGED)
    }

    /** Runs [block], turning any failure (except cancellation) into an [AppError]. */
    private suspend fun <T> guard(block: suspend () -> T): Outcome<T> = try {
        Outcome.Ok(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Outcome.Err(AppError.from(e))
    }

    /** One poll. A network blip or timeout counts as "not approved yet". */
    private suspend fun approved(via: ServerAddress, net: ServerNetConfig, secret: String): Boolean = try {
        gateway.quickConnectState(via, net, secret)
    } catch (e: AppError) {
        if (e.kind != AppError.Kind.NETWORK && e.kind != AppError.Kind.TIMEOUT) throw e
        false
    }

    /**
     * Username/password sign-in on [route]. The server is read from the store now and its identity checked on the
     * route's address before the password is sent; the password is sent once, never stored, and [password] is
     * overwritten before this returns (the `String` handed to the gateway cannot be wiped, but is not kept).
     *
     * @throws IllegalArgumentException if [route] did not come from [signInRoute].
     */
    suspend fun signInWithPassword(route: SignInRoute, username: String, password: CharArray): Account {
        try {
            val issued = issued(route)
            val server = current(issued)
            val net = anonymousNet(server)
            verifyServer(server, issued.address, net)
            val result = gateway.authenticateByName(issued.address, net, username.trim(), String(password))
            return save(server, result, issued.expectedUserId)
        } finally {
            password.fill('\u0000')
        }
    }

    /**
     * Signs [userId] out: the token is revoked on the server only through a verified address (see the class
     * KDoc), within [signOutTimeoutMs], and the account is removed here regardless (even if cancelled). If no
     * address is verified the token is not sent anywhere.
     *
     * @return false if a sign-in may still be live on the server: it could not be reached, or the saved token could not be read.
     */
    suspend fun signOut(serverId: String, userId: String): Boolean {
        try {
            val token = when (val saved = store.token(serverId, userId)) {
                StoredToken.Missing -> return true // nothing saved, so nothing to revoke
                StoredToken.Unreadable -> return false // there is a sign-in, but it cannot be revoked
                is StoredToken.Found -> saved.token
            }
            val server = store.server(serverId) ?: return false
            return withTimeoutOrNull(signOutTimeoutMs) { revoke(server, userId, token) } ?: false
        } finally {
            withContext(NonCancellable) { store.removeAccount(serverId, userId) }
        }
    }

    /**
     * Removes a server and its sign-ins. Each account's token is first revoked (in parallel, by the same rules
     * as [signOut], all within [removeTimeoutMs]); the server is then deleted here regardless (even if cancelled).
     *
     * @return false if any sign-in may still be live on the server (not reached, or a saved token could not be read).
     */
    suspend fun removeServer(serverId: String): Boolean {
        try {
            val tokens = try {
                store.tokens(serverId)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return false
            }
            if (tokens.readable.isEmpty()) return tokens.unreadable == 0
            val server = store.server(serverId) ?: return false
            val revoked = withTimeoutOrNull(removeTimeoutMs) {
                coroutineScope {
                    tokens.readable.map { (userId, token) -> async { revoke(server, userId, token) } }.awaitAll().all { it }
                }
            } ?: false
            return revoked && tokens.unreadable == 0
        } finally {
            withContext(NonCancellable) { store.removeServer(serverId) }
        }
    }

    /**
     * Revokes [token] via a verified address of [server] (read from the store by the caller just before). True if
     * the server confirmed it. The caller bounds the time.
     */
    private suspend fun revoke(server: Server, userId: String, token: String): Boolean = bestEffort {
        val via = credentialAddress(server)?.address ?: throw AppError.of(AppError.Kind.NETWORK)
        verifyServer(server, via, anonymousNet(server))
        // Bound to the token being revoked and sent through a one-off client: nothing is put in the store's token map
        // or the shared client cache.
        gateway.logout(via, ServerNet.revocation(server.addresses, server, userId, device, token))
    }

    /**
     * The address a credential for [server] may go to: the selector's verified address, upgraded to the server's
     * https address when that also answers as this server. Null when nothing is verified.
     */
    private suspend fun credentialAddress(server: Server): Chosen? {
        val basis = connections.awaitOnline(server.id)?.takeIf { it.address in server.addresses } ?: return null
        val online = basis.address
        if (online.isSecure) return Chosen(online, basis)
        val secure = server.addresses.firstOrNull { it.isSecure } ?: return Chosen(online, basis)
        val verified = withTimeoutOrNull(verifyTimeoutMs) { bestEffort { verifyServer(server, secure, anonymousNet(server)) } } ?: false
        return Chosen(if (verified) secure else online, basis)
    }

    /** The address a credential may go to, and the selector connection it was derived from (what Quick Connect watches). */
    private class Chosen(val address: ServerAddress, val basis: Connection.Online)

    /** True if [block] succeeded; any failure other than cancellation is ignored. */
    private suspend fun bestEffort(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private fun issued(route: SignInRoute): IssuedRoute =
        requireNotNull(route as? IssuedRoute) { "That sign-in route was not issued by AuthRepository." }

    /** The server as saved now, and the route's address must still be one of its addresses. */
    private suspend fun current(route: IssuedRoute): Server {
        val server = store.server(route.serverId) ?: throw AppError.of(AppError.Kind.SERVER_NOT_FOUND)
        if (route.address !in server.addresses) throw AppError.of(AppError.Kind.SERVER_NOT_FOUND)
        return server
    }

    private fun anonymousNet(server: Server): ServerNetConfig =
        ServerNet.anonymous(server.addresses, server, device)

    /** The address must answer as [server] before anything is sent to it. */
    private suspend fun verifyServer(server: Server, via: ServerAddress, net: ServerNetConfig) {
        if (gateway.publicInfo(via, net).id != server.id) throw AppError.of(AppError.Kind.SERVER_MISMATCH)
    }

    /**
     * Saves the account as the active one, after checking the token was minted by [server] itself. With [expectedUserId]
     * ("Sign in again" for one profile), a sign-in as anyone else is never made active and ends in `WRONG_ACCOUNT`: if that
     * account is already on this phone its fresh sign-in is kept quietly (the server replaces this device's older one), and
     * otherwise it is revoked rather than added as a new profile.
     */
    private suspend fun save(server: Server, result: AuthResult, expectedUserId: String? = null): Account {
        if (result.serverId != server.id) throw AppError.of(AppError.Kind.SERVER_MISMATCH)
        val account = Account(server.id, result.userId, result.userName, result.accessToken)
        if (expectedUserId != null && result.userId != expectedUserId) {
            withContext(NonCancellable) {
                if (store.token(server.id, result.userId) !is StoredToken.Missing) {
                    bestEffort { store.upsertAccount(account, makeActive = false) }
                } else {
                    withTimeoutOrNull(signOutTimeoutMs) { revoke(server, result.userId, result.accessToken) }
                }
            }
            throw AppError.of(AppError.Kind.WRONG_ACCOUNT)
        }
        try {
            store.upsertAccount(account, makeActive = true)
        } catch (e: SQLiteConstraintException) {
            // The server was removed while the user was signing in.
            throw AppError.of(AppError.Kind.SERVER_NOT_FOUND, e)
        }
        return account
    }

    /** Success or failure without exceptions, so a flow can emit the failure outside any try block. */
    private sealed interface Outcome<out T> {
        class Ok<T>(val value: T) : Outcome<T>
        class Err(val error: AppError) : Outcome<Nothing>
    }

    private inline fun <T> Outcome<T>.getOrElse(onError: (AppError) -> Nothing): T = when (this) {
        is Outcome.Ok -> value
        is Outcome.Err -> onError(error)
    }

    companion object {
        const val POLL_INTERVAL_MS = 4_000L
        const val TIMEOUT_MS = 5 * 60_000L
        const val SIGN_OUT_TIMEOUT_MS = 12_000L
        const val REMOVE_TIMEOUT_MS = 6_000L
    }
}
