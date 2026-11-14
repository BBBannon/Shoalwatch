package app.offlinefin.data.servers

import app.offlinefin.net.ServerAddress

/**
 * A Jellyfin server and its per-server security settings.
 *
 * @property pinInvalid set only by the store when the saved certificate pin was damaged: [pinnedSpkiSha256]
 *   is then null, and `ServerNet` refuses to connect (`TLS_UNTRUSTED`) until the user trusts the
 *   server again, so trust is never silently widened.
 */
data class Server(
    val id: String,
    val name: String,
    val version: String,
    val homeAddress: ServerAddress?,
    val awayAddress: ServerAddress?,
    val allowCleartext: Boolean,
    val pinnedSpkiSha256: String?,
    val clientCertAlias: String?,
    val customHeaders: Map<String, String>,
    val pinInvalid: Boolean = false,
) {
    /** Addresses this server answers on (home first). */
    val addresses: List<ServerAddress> get() = listOfNotNull(homeAddress, awayAddress)

    /** Never includes addresses or headers. */
    override fun toString() = "Server($id)"
}
