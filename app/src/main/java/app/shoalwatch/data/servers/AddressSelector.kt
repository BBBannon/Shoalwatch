package app.shoalwatch.data.servers

import app.shoalwatch.core.AppError
import app.shoalwatch.net.NetworkMonitor
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.net.ServerNetConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Which address of a server is in use right now.
 *
 * A held [Online] is not proof of identity: the network may have changed since. Anything that sends a token
 * must obtain the address with [AddressSelector.awaitOnline] (or read the current state at send time).
 */
sealed interface Connection {
    data class Online(val address: ServerAddress, val isHome: Boolean) : Connection

    /**
     * Nothing answered as this server. [reason] is set when the cause is a security problem the user must act
     * on (changed or untrusted certificate, cleartext blocked, client certificate needed, damaged saved pin);
     * null for ordinary unreachability. Never includes an address.
     */
    data class Offline(val reason: AppError.Kind? = null) : Connection

    /**
     * Probing. [previous] is what was Online before the last network change or refresh, kept only so a screen
     * can say "Home network (checking...)" without flicker. It is not usable for sending anything.
     */
    data class Checking(val previous: Online? = null) : Connection
}

/**
 * Decides, live, whether to talk to a server on its home (LAN) or away (remote) address.
 *
 * Both addresses are probed concurrently with `publicInfo` and an anonymous configuration, so no token is
 * sent to an address until it has proved to be the right server (its id must match [Server.id], by
 * [sameServerId]; a different server on that address counts as unreachable). Home gets [homeTimeoutMs], away
 * [awayTimeoutMs]. If home answers correctly it wins at once and the away probe is cancelled; otherwise away
 * is used when it answers, or the server is [Connection.Offline].
 *
 * One probe loop per server id. On every [NetworkMonitor] change and every [refresh] the state becomes
 * [Connection.Checking] synchronously (so an `Online` from before the change is never still published), and a
 * probe follows: after [probeDebounceMs] for network changes (they come in storms), at once for [refresh].
 * Requests collapse: one made while a probe runs leads to exactly one more afterwards, so probes never
 * overlap, and a result that started before the latest change is discarded. Callers should [refresh] when a
 * request on the current address fails. Time is `withTimeout`/`delay`, so tests use virtual time.
 */
@OptIn(FlowPreview::class)
class AddressSelector(
    private val gateway: JellyfinGateway,
    private val monitor: NetworkMonitor,
    private val scope: CoroutineScope,
    private val device: DeviceIdentity,
    private val homeTimeoutMs: Long = HOME_TIMEOUT_MS,
    private val awayTimeoutMs: Long = AWAY_TIMEOUT_MS,
    private val probeDebounceMs: Long = PROBE_DEBOUNCE_MS,
) : ServerConnections {
    /** Everything but [requests] and [job] is guarded by `synchronized(this)`. */
    private class Entry(var server: Server) {
        val state = MutableStateFlow<Connection>(Connection.Checking())

        /** Bumped by every event that makes an in-flight probe's result stale. */
        var generation = 0L

        /** Conflated: any number of requests while a probe runs become one pending request. */
        val requests = Channel<Unit>(Channel.CONFLATED)
        lateinit var job: Job

        /** The network may have changed (or a caller asks): nothing published so far can be relied on. */
        @Synchronized fun invalidate() {
            generation++
            val current = state.value
            val previous = (current as? Connection.Online) ?: (current as? Connection.Checking)?.previous
            state.value = Connection.Checking(previous)
        }

        /** New addresses or security settings: earlier results (and the old address) no longer apply. */
        @Synchronized fun replace(newServer: Server) {
            server = newServer
            generation++
            state.value = Connection.Checking()
        }

        @Synchronized fun snapshot() = server to generation

        /** Check-and-set in one step: a result from before the latest event is dropped. */
        @Synchronized fun publish(result: Connection, startedAt: Long) {
            if (generation == startedAt) state.value = result
        }
    }

    private val entries = HashMap<String, Entry>()

    /**
     * The live connection for [server], keyed by its id. Calling again for the same id with different
     * addresses or security settings switches to the new ones: state goes back to `Checking` and it is re-probed.
     */
    override fun connection(server: Server): StateFlow<Connection> {
        synchronized(entries) {
            val existing = entries[server.id]
            if (existing != null) {
                if (synchronized(existing) { existing.server.probeSettings() != server.probeSettings() }) {
                    existing.replace(server)
                    existing.requests.trySend(Unit)
                }
                return existing.state
            }
            val entry = Entry(server)
            entries[server.id] = entry
            entry.job = scope.launch {
                launch {
                    monitor.changes
                        .onEach { entry.invalidate() }
                        .debounce(probeDebounceMs)
                        .collect { entry.requests.trySend(Unit) }
                }
                for (request in entry.requests) probeAndPublish(entry)
            }
            entry.requests.trySend(Unit)
            return entry.state
        }
    }

    /**
     * The next [Connection.Online] for [server] (verified as that server by a probe), or null if it turns out
     * [Connection.Offline] or nothing is verified within [timeoutMs]. A `Checking` state is never returned.
     *
     * The guarantee is no stronger than the monitor's: a value published before the OS delivered a
     * network-change callback can still be returned (the phone may already have moved networks). Callers must
     * treat a failed request as "the address may be stale" and call [refresh]. This is the one call
     * token-sending code should use.
     */
    override suspend fun awaitOnline(server: Server, timeoutMs: Long): Connection.Online? =
        withTimeoutOrNull(timeoutMs) {
            connection(server).first { it is Connection.Online || it is Connection.Offline } as? Connection.Online
        }

    /** Re-probes every server now (or once its current probe finishes); each goes to `Checking` at once. */
    override fun refresh() {
        synchronized(entries) {
            entries.values.forEach {
                it.invalidate()
                it.requests.trySend(Unit)
            }
        }
    }

    /** Stops probing [serverId] (cancelling any probe in flight). Call when the server is removed. */
    fun forget(serverId: String) {
        val entry = synchronized(entries) { entries.remove(serverId) } ?: return
        entry.state.value = Connection.Offline() // a collector still holding the flow must not keep showing Online
        entry.job.cancel()
        entry.requests.close()
    }

    private suspend fun probeAndPublish(entry: Entry) {
        val (server, startedAt) = entry.snapshot()
        entry.publish(probe(server), startedAt)
    }

    private suspend fun probe(server: Server): Connection {
        val net = try {
            ServerNet.anonymous(server.addresses, server, device)
        } catch (e: AppError) {
            return Connection.Offline(e.kind) // a damaged pin: nothing is sent until the user trusts the server again
        }
        return coroutineScope {
            val home = server.homeAddress?.let { async { reach(server, it, net, homeTimeoutMs) } }
            val away = server.awayAddress?.let { async { reach(server, it, net, awayTimeoutMs) } }
            val homeResult = home?.await()
            val awayResult = if (homeResult is Reach.Yes) null else away?.await()
            val result = when {
                homeResult is Reach.Yes -> Connection.Online(server.homeAddress!!, isHome = true)
                awayResult is Reach.Yes -> Connection.Online(server.awayAddress!!, isHome = false)
                else -> Connection.Offline(listOf(homeResult, awayResult).firstNotNullOfOrNull { (it as? Reach.No)?.reason })
            }
            this.coroutineContext.cancelChildren() // a slower probe that is no longer needed
            result
        }
    }

    private sealed interface Reach {
        data object Yes : Reach
        class No(val reason: AppError.Kind?) : Reach
    }

    /**
     * [Reach.Yes] only if [address] answers within [timeoutMs] as [server] itself. Cancelling the probe cancels
     * its HTTP call. A security failure is kept as the reason; everything else is plain unreachability.
     */
    private suspend fun reach(server: Server, address: ServerAddress, net: ServerNetConfig, timeoutMs: Long): Reach =
        withTimeoutOrNull(timeoutMs) {
            try {
                if (sameServerId(gateway.publicInfo(address, net).id, server.id)) Reach.Yes else Reach.No(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Reach.No(AppError.from(e).kind.takeIf { it in SECURITY_KINDS })
            }
        } ?: Reach.No(null)

    /** What a probe depends on: when any of it changes, earlier results no longer apply. */
    private fun Server.probeSettings() = listOf(homeAddress, awayAddress, allowCleartext, pinnedSpkiSha256, clientCertAlias, customHeaders, pinInvalid)

    companion object {
        const val HOME_TIMEOUT_MS = 1_500L
        const val AWAY_TIMEOUT_MS = 5_000L
        const val PROBE_DEBOUNCE_MS = 500L
        const val AWAIT_TIMEOUT_MS = 6_000L

        private val SECURITY_KINDS = setOf(
            AppError.Kind.TLS_PIN_CHANGED, AppError.Kind.TLS_UNTRUSTED,
            AppError.Kind.CLEARTEXT_BLOCKED, AppError.Kind.CLIENT_CERT_REQUIRED,
        )
    }
}
