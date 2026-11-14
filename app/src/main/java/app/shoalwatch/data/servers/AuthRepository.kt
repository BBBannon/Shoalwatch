package app.shoalwatch.data.servers

import android.database.sqlite.SQLiteConstraintException
import app.shoalwatch.core.AppError
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.net.ServerNetConfig
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CancellationException

/**
 * Finding a server and signing in. Successful sign-ins are saved to [ServerStore] and made active.
 * Time is `delay`/`withTimeout`, so tests run it in virtual time.
 *
 * Passwords and Quick Connect secrets are never stored or logged.
 */
class AuthRepository(
    private val gateway: JellyfinGateway,
    private val store: ServerStore,
    private val device: DeviceIdentity,
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val timeoutMs: Long = TIMEOUT_MS,
) {
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
     * Quick Connect as a cold flow: collecting it starts the attempt, cancelling collection abandons it
     * (and stops polling). First checks the address really is [server] (nothing is requested of an
     * impostor), then emits Starting, AwaitingApproval, polls every 4 s for up to 5 minutes, then
     * Authenticating and Success, or ends with TimedOut / Failed. Network and timeout errors while polling
     * are tolerated; anything else fails at once. The flow completes after a terminal state.
     *
     * Only the gateway calls are guarded: an exception thrown by the collector is never swallowed.
     *
     * @throws IllegalArgumentException if [via] is not one of [server]'s addresses.
     */
    fun quickConnect(server: Server, via: ServerAddress): Flow<QuickConnectState> {
        requireOwn(server, via)
        return flow {
            emit(QuickConnectState.Starting)
            val (net, session) = guard { begin(server, via) }.getOrElse { emit(QuickConnectState.Failed(it)); return@flow }
            emit(QuickConnectState.AwaitingApproval(session.code))

            val approved = guard { awaitApproval(via, net, session.secret) }.getOrElse { emit(QuickConnectState.Failed(it)); return@flow }
            if (!approved) {
                emit(QuickConnectState.TimedOut)
                return@flow
            }
            emit(QuickConnectState.Authenticating)
            val account = guard {
                save(server, gateway.authenticateWithQuickConnect(via, net, session.secret))
            }.getOrElse { emit(QuickConnectState.Failed(it)); return@flow }
            emit(QuickConnectState.Success(account))
        }
    }

    /** Verifies the server's identity, then asks for a code. */
    private suspend fun begin(server: Server, via: ServerAddress): Pair<ServerNetConfig, QcSession> {
        val net = anonymousNet(server)
        verifyServer(server, via, net)
        if (!gateway.quickConnectEnabled(via, net)) throw AppError.of(AppError.Kind.QUICK_CONNECT_DISABLED)
        return net to gateway.quickConnectInitiate(via, net)
    }

    /** Polls until approved (true) or [timeoutMs] passes (false). */
    private suspend fun awaitApproval(via: ServerAddress, net: ServerNetConfig, secret: String): Boolean =
        withTimeoutOrNull(timeoutMs) {
            while (!approved(via, net, secret)) delay(pollIntervalMs)
            true
        } ?: false

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
     * Username/password sign-in. The address must be [server] (checked before the password is sent), the
     * password is sent once, never stored, and [password] is overwritten before this returns (the `String`
     * handed to the gateway cannot be wiped, but is not kept).
     */
    suspend fun signInWithPassword(server: Server, via: ServerAddress, username: String, password: CharArray): Account {
        try {
            requireOwn(server, via)
            val net = anonymousNet(server)
            verifyServer(server, via, net)
            val result = gateway.authenticateByName(via, net, username.trim(), String(password))
            return save(server, result)
        } finally {
            password.fill('\u0000')
        }
    }

    /**
     * Revokes the token on the server (best effort: each address in turn until one works), then removes the
     * account here regardless. Removal also drops the cached clients, and the token provider yields no token
     * from then on.
     */
    suspend fun signOut(serverId: String, userId: String) {
        try {
            val server = store.server(serverId)
            val account = store.accounts(serverId).first().firstOrNull { it.userId == userId }
            if (server != null && account != null) {
                bestEffort {
                    // Bound to the token being revoked, for this call only: nothing is put back in the store's token map.
                    val token = account.accessToken
                    val net = ServerNet.authenticated(server.addresses, server, userId, device) { _, _ -> token }
                    for (address in server.addresses) {
                        if (bestEffort { gateway.logout(address, net) }) break
                    }
                }
            }
        } finally {
            withContext(NonCancellable) { store.removeAccount(serverId, userId) }
        }
    }

    /** True if [block] succeeded; any failure other than cancellation is ignored. */
    private suspend fun bestEffort(block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }

    private fun anonymousNet(server: Server): ServerNetConfig =
        ServerNet.anonymous(server.addresses, server, device)

    private fun requireOwn(server: Server, via: ServerAddress) =
        require(via in server.addresses) { "That address does not belong to this server." }

    /** The address must answer as [server] before anything is sent to it. */
    private suspend fun verifyServer(server: Server, via: ServerAddress, net: ServerNetConfig) {
        if (!sameServerId(gateway.publicInfo(via, net).id, server.id)) throw AppError.of(AppError.Kind.SERVER_MISMATCH)
    }

    /** Saves the account as the active one, after checking the token was minted by [server] itself. */
    private suspend fun save(server: Server, result: AuthResult): Account {
        if (!sameServerId(result.serverId, server.id)) throw AppError.of(AppError.Kind.SERVER_MISMATCH)
        val account = Account(server.id, result.userId, result.userName, result.accessToken)
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
    }
}

/** True when both ids are non-empty and equal ignoring case and dashes (Jellyfin reports ids with or without them). */
internal fun sameServerId(a: String, b: String): Boolean {
    fun normalised(id: String) = id.lowercase().replace("-", "")
    return normalised(a).isNotEmpty() && normalised(a) == normalised(b)
}
