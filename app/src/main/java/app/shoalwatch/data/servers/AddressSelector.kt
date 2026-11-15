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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Decides, live, whether to talk to a server on its home (LAN) or away (remote) address.
 *
 * Callers name a server by id only. Each probe reads that server's addresses and security settings from
 * [source] when it starts, and the selector watches [source], so a change of addresses, pin, cleartext consent,
 * client certificate or headers resets the state to `Checking` and re-probes. No caller can hand it an older copy
 * of a server, and a server that is no longer saved is [Connection.Offline] without anything being sent.
 *
 * Both addresses are probed concurrently with `publicInfo` and an anonymous configuration, so no token is sent
 * to an address until it has proved to be the right server (its id must equal [Server.id]; ids are normalised at
 * the gateway, see [JellyfinId]; a different server on that address counts as unreachable). Home gets
 * [homeTimeoutMs], away [awayTimeoutMs]. If home answers correctly it wins at once and the away probe is
 * cancelled; otherwise away is used when it answers, or the server is [Connection.Offline].
 *
 * One probe loop per server id. On every [NetworkMonitor] change and every [refresh] the state becomes
 * [Connection.Checking] synchronously (so an `Online` from before the change is never still published), and a
 * probe follows: after [probeDebounceMs] for network changes (they come in storms), at once for [refresh].
 * Requests collapse: one made while a probe runs leads to exactly one more afterwards, so probes never
 * overlap, and a result that started before the latest change is discarded. Time is `withTimeout`/`delay`,
 * so tests use virtual time.
 */
@OptIn(FlowPreview::class)
class AddressSelector(
    private val gateway: JellyfinGateway,
    private val monitor: NetworkMonitor,
    private val scope: CoroutineScope,
    private val device: DeviceIdentity,
    private val source: ServerSettingsSource,
    /** True while the app is in the foreground (a screen of it is started); see [recoverWhileWatched]. */
    private val foreground: Flow<Boolean>,
    private val homeTimeoutMs: Long = HOME_TIMEOUT_MS,
    private val awayTimeoutMs: Long = AWAY_TIMEOUT_MS,
    private val probeDebounceMs: Long = PROBE_DEBOUNCE_MS,
) : ServerConnections {
    /** Everything but [requests] and [job] is guarded by `synchronized(this)`. */
    private class Entry(val serverId: String) {
        val state = MutableStateFlow<Connection>(Connection.Checking())

        /** Bumped by every event that makes an in-flight probe's result stale. */
        var generation = 0L

        /** What the last probe was started with (null before the first): see [probeSettings]. */
        private var settings: List<Any?>? = null
        private var probed = false

        /** Conflated: any number of requests while a probe runs become one pending request. */
        val requests = Channel<Unit>(Channel.CONFLATED)
        lateinit var job: Job

        /** How long the next unprompted re-probe of an `Offline` server waits; see [AddressSelector.recoverWhileWatched]. */
        @Volatile var recoveryWaitMs = RECOVERY_MIN_MS

        /** The last probe found no saved server with this id: there is nothing to re-probe until the store changes. */
        @Volatile var unsaved = false

        /** The network may have changed (or a caller asks): nothing published so far can be relied on. */
        @Synchronized fun invalidate() {
            generation++
            val current = state.value
            val previous = (current as? Connection.Online) ?: (current as? Connection.Checking)?.previous
            state.value = Connection.Checking(previous)
        }

        /** [invalidate], but only if the published state is still exactly [expected] (the same instance, not merely an equal one). */
        @Synchronized fun invalidateIf(expected: Connection.Online): Boolean {
            if (state.value !== expected) return false
            invalidate()
            return true
        }

        /**
         * A probe starts with the settings just read from the store. If they differ from what the last probe used,
         * earlier results (and the old address) no longer apply: back to `Checking`, and older in-flight results are dropped.
         */
        @Synchronized fun begin(current: List<Any?>?): Long {
            if (probed && current != settings) {
                generation++
                state.value = Connection.Checking()
            }
            settings = current
            probed = true
            return generation
        }

        /**
         * The store now holds [current]: if that differs from what the last probe used, the published state no longer
         * applies, so it goes back to `Checking` at once (and any in-flight result is dropped). True if a probe is needed.
         */
        @Synchronized fun resetIfStale(current: List<Any?>?): Boolean {
            if (!probed || current == settings) return false
            generation++
            state.value = Connection.Checking()
            return true
        }

        /** The store can no longer be watched: what was shown is not to be trusted, and older in-flight results are dropped. */
        @Synchronized fun failClosed() {
            generation++
            state.value = Connection.Offline()
        }

        /** Check-and-set in one step: a result from before the latest event is dropped. */
        @Synchronized fun publish(result: Connection, startedAt: Long) {
            if (generation == startedAt) state.value = result
        }
    }

    private val entries = HashMap<String, Entry>()

    /**
     * Started by the first [connection]: a server whose saved settings change (or which is removed) is re-probed.
     * If the store's flow fails, every server shown is published `Offline` and in-flight results are dropped, and the
     * flow is subscribed again after a backoff of 1 s doubling to 30 s, reset by a healthy emission. The first
     * emission after a failure re-probes every live server. This is best effort, not a guarantee: a probe that starts
     * during the outage reads the store directly and can still publish `Online`, and settings changes made meanwhile
     * are picked up within the backoff. That is safe because `AuthRepository` re-reads the store before sending.
     */
    private val watcher: Job by lazy {
        scope.launch {
            var backoff = WATCH_BACKOFF_MIN_MS
            var recovering = false
            while (true) {
                try {
                    source.servers.collect { saved ->
                        backoff = WATCH_BACKOFF_MIN_MS
                        val byId = saved.associateBy { it.id }
                        val live = synchronized(entries) { entries.values.toList() }
                        for (entry in live) {
                            val stale = entry.resetIfStale(byId[entry.serverId]?.probeSettings())
                            if (stale || recovering) entry.requests.trySend(Unit)
                        }
                        recovering = false
                    }
                    return@launch
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    synchronized(entries) { entries.values.toList() }.forEach { it.failClosed() }
                    recovering = true
                    delay(backoff)
                    backoff = minOf(backoff * 2, WATCH_BACKOFF_MAX_MS)
                }
            }
        }
    }

    /** The live connection for the server with [serverId]. One probe loop per id, shared by every caller. */
    override fun connection(serverId: String): StateFlow<Connection> {
        watcher.start()
        synchronized(entries) {
            entries[serverId]?.let { return it.state }
            val entry = Entry(serverId)
            entries[serverId] = entry
            entry.job = scope.launch {
                launch {
                    monitor.changes
                        .onEach { entry.recoveryWaitMs = RECOVERY_MIN_MS; entry.invalidate() }
                        .debounce(probeDebounceMs)
                        .collect { entry.requests.trySend(Unit) }
                }
                launch { recoverWhileWatched(entry) }
                for (request in entry.requests) probeAndPublish(entry)
            }
            entry.requests.trySend(Unit)
            return entry.state
        }
    }

    /**
     * The next [Connection.Online] for [serverId], or null if it turns out [Connection.Offline] or nothing is
     * verified within [timeoutMs]. A `Checking` state is never returned.
     *
     * What it proves: the address answered `/System/Info/Public` with this server's id, using the settings saved
     * when the probe started, since the last network change the OS reported. What it does NOT prove: that the
     * answer came from the real server (on plain http anyone holding that address can repeat the public id), or
     * that the phone has not moved network since (a change the OS has not reported yet). So it is where credential
     * code starts, not the whole check: `AuthRepository` re-reads the server, prefers a verified https address and
     * repeats the identity check on the chosen address immediately before sending. A caller whose request fails
     * should call [refresh].
     */
    override suspend fun awaitOnline(serverId: String, timeoutMs: Long): Connection.Online? =
        withTimeoutOrNull(timeoutMs) {
            connection(serverId).first { it is Connection.Online || it is Connection.Offline } as? Connection.Online
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

    /** Nothing happens for a server whose connection nobody has asked for, or one that was removed: no probing is started for it. */
    override fun refresh(serverId: String) {
        // One lock for the look-up and the reset, so a [forget] cannot fall between them and leave an orphan entry `Checking`.
        synchronized(entries) {
            val entry = entries[serverId] ?: return
            entry.invalidate()
            entry.requests.trySend(Unit)
        }
    }

    override fun refreshIfOffline(serverId: String) {
        synchronized(entries) {
            val entry = entries[serverId] ?: return
            if (entry.state.value !is Connection.Offline) return
            entry.invalidate()
            entry.requests.trySend(Unit)
        }
    }

    /**
     * An `Offline` server is re-probed without the user asking, so a server that was down and came back is found again (the
     * network did not change, so nothing else would re-check it). Only while BOTH something is collecting [connection] for it (a
     * screen's ViewModel is alive) AND the app is in the [foreground] (one of its screens is started): ViewModels live on while
     * the app is in the background, so the first alone would probe all night. Only for ordinary unreachability of a saved server:
     * a security reason (changed or untrusted certificate, cleartext blocked, client certificate needed, damaged pin) waits for
     * the user. The wait starts at [RECOVERY_MIN_MS] and doubles up to [RECOVERY_MAX_MS]; it starts again when the server is
     * `Online`, on a network change, and on coming back to the foreground, which also probes at once. Each probe is a small
     * anonymous request or two, at most one a minute once settled, and none while the app is in the background.
     *
     * These probes are quiet: the state is not set to `Checking` first, so a probe that fails again publishes an equal `Offline`
     * and nothing on screen changes or is announced; only a success (or a different reason) is published. A user's own refresh
     * still shows `Checking`. Time is `delay`, so tests use virtual time.
     */
    private suspend fun recoverWhileWatched(entry: Entry) {
        // This function collects the state itself, so one watcher of its own is always counted.
        val retryable = entry.state.map { c ->
            if (c is Connection.Online) entry.recoveryWaitMs = RECOVERY_MIN_MS
            c is Connection.Offline && c.reason !in SECURITY_KINDS
        }
        var wasInBackground = false
        combine(retryable, entry.state.subscriptionCount.map { it > 1 }, foreground) { offline, watched, front -> Triple(offline, watched, front) }
            .distinctUntilChanged()
            .collectLatest { (offline, watched, front) ->
                if (!front) {
                    wasInBackground = true
                    return@collectLatest
                }
                val resumed = wasInBackground
                wasInBackground = false
                if (!offline || !watched) return@collectLatest
                if (resumed) {
                    entry.recoveryWaitMs = RECOVERY_MIN_MS
                    reprobeQuietly(entry)
                }
                while (true) {
                    delay(entry.recoveryWaitMs)
                    entry.recoveryWaitMs = minOf(entry.recoveryWaitMs * 2, RECOVERY_MAX_MS)
                    reprobeQuietly(entry)
                }
            }
    }

    /** Asks for a probe without going through `Checking` (see [recoverWhileWatched]); nothing if the server is no longer saved. */
    private fun reprobeQuietly(entry: Entry) {
        if (!entry.unsaved && entry.state.value is Connection.Offline) entry.requests.trySend(Unit)
    }

    /**
     * Compared by identity: [awaitOnline] hands out the instance that was published, and every verification publishes a
     * new one, so a failure from before the latest verification (even of the same address) never discards it. Only
     * [serverId] is re-probed; [refresh] stays for the user's "retry".
     */
    override fun refreshIfCurrent(serverId: String, failed: Connection.Online) {
        val entry = synchronized(entries) { entries[serverId] } ?: return
        if (entry.invalidateIf(failed)) entry.requests.trySend(Unit)
    }

    /** Stops probing [serverId] (cancelling any probe in flight). Called when the server is removed. */
    fun forget(serverId: String) {
        val entry = synchronized(entries) { entries.remove(serverId) } ?: return
        entry.state.value = Connection.Offline() // a collector still holding the flow must not keep showing Online
        entry.job.cancel()
        entry.requests.close()
    }

    /** Reads the server from the store now; one that is no longer saved is Offline and nothing is sent. Never throws. */
    private suspend fun probeAndPublish(entry: Entry) {
        try {
            val server = source.server(entry.serverId)
            val startedAt = entry.begin(server?.probeSettings())
            entry.unsaved = server == null
            entry.publish(if (server == null) Connection.Offline() else probe(server), startedAt)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A store failure must not end the loop or leave a stale Online on show (review m12).
            entry.state.value = Connection.Offline()
        }
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
                if (gateway.publicInfo(address, net).id == server.id) Reach.Yes else Reach.No(null)
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
        const val WATCH_BACKOFF_MIN_MS = 1_000L
        const val WATCH_BACKOFF_MAX_MS = 30_000L
        const val RECOVERY_MIN_MS = 5_000L
        const val RECOVERY_MAX_MS = 60_000L

        private val SECURITY_KINDS = setOf(
            AppError.Kind.TLS_PIN_CHANGED, AppError.Kind.TLS_UNTRUSTED,
            AppError.Kind.CLEARTEXT_BLOCKED, AppError.Kind.CLIENT_CERT_REQUIRED,
        )
    }
}
