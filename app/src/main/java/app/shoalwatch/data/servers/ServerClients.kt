package app.shoalwatch.data.servers

import app.shoalwatch.net.HttpClientFactory
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.net.ServerNetConfig
import okhttp3.OkHttpClient

/**
 * Holds one API client per server configuration (by value: see [ServerNetConfig.equals]), so separate
 * `ServerNet.anonymous(...)` / `authenticated(...)` calls with the same settings share a client and its SSLContext. Clients are built
 * by [HttpClientFactory] (the only place that constructs them) and derived with redirects off, because API
 * calls carry credentials in bodies and queries that OkHttp would otherwise resend to a redirect target.
 *
 * The first configuration seen for a key supplies the token provider, so providers must read their token live.
 * Call [evict] when a server's accounts or settings change; a small LRU bounds the rest.
 */
class ServerClients(private val factory: HttpClientFactory) {
    private val clients = object : LinkedHashMap<ServerNetConfig, OkHttpClient>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ServerNetConfig, OkHttpClient>?) = size > MAX_CLIENTS
    }

    fun clientFor(net: ServerNetConfig): OkHttpClient = synchronized(clients) {
        clients.getOrPut(net) { factory.clientFor(net).newBuilder().followRedirects(false).build() }
    }

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
