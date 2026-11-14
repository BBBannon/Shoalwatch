package app.offlinefin.data.servers

import app.offlinefin.core.AppError
import app.offlinefin.net.ServerAddress
import app.offlinefin.net.ServerNetConfig
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
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
                return gateway.publicInfo(address, ServerNet.config(listOf(address), draft) { device.authHeader(null) })
            } catch (e: AppError) {
                if (first == null) first = e
            }
        }
        throw first!!
    }

    /**
     * Quick Connect as a cold flow: collecting it starts the attempt, cancelling collection abandons it
     * (and stops polling). Emits Starting, AwaitingApproval, then polls every 4 s for up to 5 minutes
     * before Authenticating and Success, or ends with TimedOut / Failed. Network and timeout errors
     * while polling are tolerated; anything else fails at once. The flow completes after a terminal state.
     *
     * @throws IllegalArgumentException if [via] is not one of [server]'s addresses.
     */
    fun quickConnect(server: Server, via: ServerAddress): Flow<QuickConnectState> {
        requireOwn(server, via)
        return flow {
            emit(QuickConnectState.Starting)
            val terminal = try {
                attempt(server, via)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                QuickConnectState.Failed(AppError.from(e))
            }
            emit(terminal)
        }
    }

    private suspend fun FlowCollector<QuickConnectState>.attempt(server: Server, via: ServerAddress): QuickConnectState {
        val net = anonymousNet(server)
        if (!gateway.quickConnectEnabled(via, net)) throw AppError.of(AppError.Kind.QUICK_CONNECT_DISABLED)
        val session = gateway.quickConnectInitiate(via, net)
        emit(QuickConnectState.AwaitingApproval(session.code))

        withTimeoutOrNull(timeoutMs) {
            while (!approved(via, net, session.secret)) delay(pollIntervalMs)
            true
        } ?: return QuickConnectState.TimedOut

        emit(QuickConnectState.Authenticating)
        val result = gateway.authenticateWithQuickConnect(via, net, session.secret)
        return QuickConnectState.Success(save(server, result))
    }

    /** One poll. A network blip or timeout counts as "not approved yet". */
    private suspend fun approved(via: ServerAddress, net: ServerNetConfig, secret: String): Boolean = try {
        gateway.quickConnectState(via, net, secret)
    } catch (e: AppError) {
        if (e.kind != AppError.Kind.NETWORK && e.kind != AppError.Kind.TIMEOUT) throw e
        false
    }

    /**
     * Username/password sign-in. The password is sent once, never stored, and [password] is overwritten
     * before this returns (the `String` handed to the gateway cannot be wiped, but is not kept).
     */
    suspend fun signInWithPassword(server: Server, via: ServerAddress, username: String, password: CharArray): Account {
        try {
            requireOwn(server, via)
            val result = gateway.authenticateByName(via, anonymousNet(server), username.trim(), String(password))
            return save(server, result)
        } finally {
            password.fill('\u0000')
        }
    }

    /**
     * Revokes the token on the server (best effort, the first address), then removes the account here
     * regardless of whether the server could be reached.
     */
    suspend fun signOut(serverId: String, userId: String) {
        try {
            val server = store.server(serverId)
            val account = store.accounts(serverId).first().firstOrNull { it.userId == userId }
            val address = server?.addresses?.firstOrNull()
            if (server != null && account != null && address != null) {
                try {
                    val net = ServerNet.config(server.addresses, server) { device.authHeader(account.accessToken) }
                    gateway.logout(address, net)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    // Best effort: an unreachable server must not stop the user signing out here.
                }
            }
        } finally {
            withContext(NonCancellable) { store.removeAccount(serverId, userId) }
        }
    }

    private fun anonymousNet(server: Server): ServerNetConfig =
        ServerNet.config(server.addresses, server) { device.authHeader(null) }

    private fun requireOwn(server: Server, via: ServerAddress) =
        require(via in server.addresses) { "That address does not belong to this server." }

    private suspend fun save(server: Server, result: AuthResult): Account {
        val account = Account(server.id, result.userId, result.userName, result.accessToken)
        store.upsertAccount(account, makeActive = true)
        return account
    }

    companion object {
        const val POLL_INTERVAL_MS = 4_000L
        const val TIMEOUT_MS = 5 * 60_000L
    }
}
