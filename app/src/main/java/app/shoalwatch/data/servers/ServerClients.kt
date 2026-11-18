package app.shoalwatch.data.servers

import app.shoalwatch.net.HttpClientFactory
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.net.ServerNetConfig
import okhttp3.OkHttpClient

/**
 * Holds one API client per server configuration (by value: see [ServerNetConfig.equals]), so separate
 * `ServerNet.anonymous(...)` / `authenticated(...)` calls with the same settings share a client and its SSLContext. Clients are built
 * by [HttpClientFactory] (the only place that constructs them), which never follows redirects.
 *
 * The first configuration seen for a key supplies the token provider, so providers must read their token live.
 * Call [evict] when a server's accounts or settings change; a small LRU bounds the rest.
 *
 * [oneOff] is the other way in: a client built for a single use (revoking a token) that is never stored here, so it
 * can neither take over nor reuse the provider of a live per-account client.
 *
 * Connections are only ever closed through [closeLater], never on the caller's thread: closing a TLS connection writes a
 * close_notify, which Android refuses on the main thread (`NetworkOnMainThreadException`), and [evict] runs from the account
 * and settings hooks, which can be on the main thread (a Quick Connect sign-in on an https server failed that way, 18/11/2026).
 * A dropped client is already out of the cache before its close is queued, so nothing new uses it meanwhile.
 */
class ServerClients(
    private val factory: HttpClientFactory,
    private val closeLater: (Runnable) -> Unit = ::closeInBackground,
) {
    private val clients = object : LinkedHashMap<ServerNetConfig, OkHttpClient>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ServerNetConfig, OkHttpClient>?) = size > MAX_CLIENTS
    }

    /** @throws IllegalArgumentException for a revocation configuration: its captured token must never be cached (use [oneOff]). */
    fun clientFor(net: ServerNetConfig): OkHttpClient = synchronized(clients) {
        require(net.authKey?.startsWith(ServerNet.REVOCATION_KEY_PREFIX) != true) { "A revocation configuration must use a one-off client." }
        clients.getOrPut(net) { factory.clientFor(net) }
    }

    /**
     * Runs [block] with a client built just for it from [net] and never placed in the cache; the (shared) connection
     * pool is trimmed of idle connections afterwards, whatever happens. [net] may carry a token captured as a string: nothing outlives this call.
     */
    suspend fun <T> oneOff(net: ServerNetConfig, block: suspend (OkHttpClient) -> T): T {
        val client = factory.clientFor(net)
        try {
            return block(client)
        } finally {
            val pool = client.connectionPool
            closeLater { pool.evictAll() }
        }
    }

    /**
     * Whether [client] is still the cached client for its configuration. False once [evict] (a change of the server's settings
     * or accounts) or the LRU has dropped it: a holder of a long-lived client (a media route) must then stop using it.
     */
    fun isCurrent(client: OkHttpClient): Boolean = synchronized(clients) { clients.values.any { it === client } }

    /** How many clients are cached (for tests: proves a one-off left none behind). */
    internal fun cachedCount(): Int = synchronized(clients) { clients.size }

    /** Drops every client for [serverId] (signed-in keys `serverId:...`) or answering on any of [addresses]. */
    fun evict(serverId: String, addresses: Collection<ServerAddress>) {
        val authorities = addresses.map { it.authority }.toSet()
        val dropped = synchronized(clients) {
            val doomed = clients.keys.filter { key ->
                key.authKey?.startsWith("$serverId:") == true || key.authorities.any { it in authorities }
            }
            doomed.mapNotNull { clients.remove(it) }
        }
        if (dropped.isNotEmpty()) closeLater { dropped.forEach { it.connectionPool.evictAll() } }
    }

    companion object {
        private const val MAX_CLIENTS = 8

        /** One daemon thread, so closes queue in order and never keep the process alive. */
        private val closer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "shoalwatch-close").apply { isDaemon = true }
        }

        /** The default [closeLater]: off the caller's thread; a close that fails is of no interest to anyone. */
        fun closeInBackground(work: Runnable) = closer.execute { runCatching { work.run() } }
    }
}
