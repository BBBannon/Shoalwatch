package app.offlinefin.data.servers

import app.offlinefin.core.AppError
import app.offlinefin.net.NetworkMonitor
import app.offlinefin.net.ServerAddress
import app.offlinefin.net.ServerNetConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Which address of a server is in use right now. */
sealed interface Connection {
    data class Online(val address: ServerAddress, val isHome: Boolean) : Connection
    data object Offline : Connection
    data object Checking : Connection
}

/**
 * Decides, live, whether to talk to a server on its home (LAN) or away (remote) address.
 *
 * Both addresses are probed concurrently with `publicInfo` and an anonymous configuration, so no token is
 * sent to an address until it has proved to be the right server (its id must match [Server.id], by
 * [sameServerId]; a different server on that address counts as unreachable). Home gets [homeTimeoutMs], away
 * [awayTimeoutMs]. If home answers correctly it wins at once and the away probe is cancelled; otherwise away
 * is used as soon as it has answered, or the server is [Connection.Offline].
 *
 * One probe loop per server id. A probe is requested at the start, on every [NetworkMonitor] change, on
 * [refresh] and when [connection] sees changed settings. Requests collapse: one made while a probe runs
 * leads to exactly one more afterwards, so probes never overlap. Callers should [refresh] when a request on
 * the current address fails. After the first probe the last known state stays visible while re-probing
 * (never `Checking` again, unless the server's settings changed). Time is `withTimeout`, so tests use
 * virtual time.
 */
class AddressSelector(
    private val gateway: JellyfinGateway,
    private val monitor: NetworkMonitor,
    private val scope: CoroutineScope,
    private val device: DeviceIdentity,
    private val homeTimeoutMs: Long = HOME_TIMEOUT_MS,
    private val awayTimeoutMs: Long = AWAY_TIMEOUT_MS,
) {
    private class Entry(@Volatile var server: Server) {
        val state = MutableStateFlow<Connection>(Connection.Checking)
        /** Conflated: any number of requests while a probe runs become one pending request. */
        val requests = Channel<Unit>(Channel.CONFLATED)
        lateinit var job: Job
    }

    private val entries = HashMap<String, Entry>()

    /**
     * The live connection for [server], keyed by its id. Calling again for the same id with different
     * addresses or security settings switches to the new ones: state goes back to `Checking` and it is re-probed.
     */
    fun connection(server: Server): StateFlow<Connection> {
        synchronized(entries) {
            val existing = entries[server.id]
            if (existing != null) {
                if (existing.server.probeSettings() != server.probeSettings()) {
                    existing.server = server
                    existing.state.value = Connection.Checking
                    existing.requests.trySend(Unit)
                }
                return existing.state
            }
            val entry = Entry(server)
            entries[server.id] = entry
            entry.job = scope.launch {
                launch { monitor.changes.collect { entry.requests.trySend(Unit) } }
                for (request in entry.requests) publish(entry)
            }
            entry.requests.trySend(Unit)
            return entry.state
        }
    }

    /** Re-probes every server now (or once its current probe finishes). */
    fun refresh() {
        synchronized(entries) { entries.values.forEach { it.requests.trySend(Unit) } }
    }

    /** Stops probing [serverId] (cancelling any probe in flight). Call when the server is removed. */
    fun forget(serverId: String) {
        val entry = synchronized(entries) { entries.remove(serverId) } ?: return
        entry.job.cancel()
        entry.requests.close()
    }

    private suspend fun publish(entry: Entry) {
        val server = entry.server
        val result = probe(server)
        // Settings changed mid-probe: this result is about the old ones, and another probe is already queued.
        if (entry.server === server) entry.state.value = result
    }

    private suspend fun probe(server: Server): Connection {
        val net = try {
            ServerNet.anonymous(server.addresses, server, device)
        } catch (_: AppError) {
            return Connection.Offline // a damaged pin: nothing is sent until the user trusts the server again
        }
        return coroutineScope {
            val home = server.homeAddress?.let { async { reachable(server, it, net, homeTimeoutMs) } }
            val away = server.awayAddress?.let { async { reachable(server, it, net, awayTimeoutMs) } }
            val result = when {
                home != null && home.await() -> Connection.Online(server.homeAddress!!, isHome = true)
                away != null && away.await() -> Connection.Online(server.awayAddress!!, isHome = false)
                else -> Connection.Offline
            }
            coroutineContext.cancelChildren() // a slower probe that is no longer needed
            result
        }
    }

    /** True only if [address] answers within [timeoutMs] as [server] itself. Cancelling the probe cancels its HTTP call. */
    private suspend fun reachable(server: Server, address: ServerAddress, net: ServerNetConfig, timeoutMs: Long): Boolean =
        withTimeoutOrNull(timeoutMs) {
            try {
                sameServerId(gateway.publicInfo(address, net).id, server.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false // any failure, including one the gateway did not map, just means "not reachable"
            }
        } ?: false

    /** What a probe depends on: when any of it changes, earlier results no longer apply. */
    private fun Server.probeSettings() = listOf(homeAddress, awayAddress, allowCleartext, pinnedSpkiSha256, clientCertAlias, customHeaders, pinInvalid)

    companion object {
        const val HOME_TIMEOUT_MS = 1_500L
        const val AWAY_TIMEOUT_MS = 5_000L
    }
}
