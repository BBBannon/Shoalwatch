package app.shoalwatch.data.servers

import app.shoalwatch.net.ServerAddress

/**
 * A guess at whether [this] is a home (local network) address: private IPv4 ranges, loopback and link-local,
 * IPv6 unique-local and link-local, `.local` names and single-label hosts (`nas`, `jellyfin`). Anything else
 * (a public name or address) is treated as away. Only used to pre-sort the first address the user types.
 */
fun ServerAddress.looksLikeHome(): Boolean {
    val h = host.lowercase()
    if (h == "localhost" || h.endsWith(".local")) return true
    val octets = h.split('.').map { it.toIntOrNull() }
    if (octets.size == 4 && octets.all { it != null && it in 0..255 }) {
        val a = octets[0]!!
        val b = octets[1]!!
        return a == 10 || a == 127 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
    }
    if (':' in h) {
        return h == "::1" || h.startsWith("fc") || h.startsWith("fd") ||
            h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") || h.startsWith("feb")
    }
    return '.' !in h
}
