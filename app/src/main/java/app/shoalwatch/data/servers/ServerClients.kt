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
 */
class ServerClients(private val factory: HttpClientFactory) {
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
            client.connectionPool.evictAll()
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
        dropped.forEach { it.connectionPool.evictAll() }
    }

    private companion object {
        const val MAX_CLIENTS = 8
    }
}
