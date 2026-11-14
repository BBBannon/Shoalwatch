package app.offlinefin.data.servers

import app.offlinefin.core.AppError
import app.offlinefin.net.ServerAddress
import app.offlinefin.net.ServerNetConfig

/** A server's security settings before a [Server] exists (used while adding one). */
data class ServerNetDraft(
    val allowCleartext: Boolean = false,
    val pinnedSpkiSha256: String? = null,
    val clientCertAlias: String? = null,
    val customHeaders: Map<String, String> = emptyMap(),
) {
    /** Never prints headers. */
    override fun toString() = "ServerNetDraft(<redacted>)"
}

/** The single place where a server's security settings become a client configuration. */
object ServerNet {
    /**
     * @param addresses the addresses that count as "this server" (nulls are ignored); the login token and
     *   custom headers are only ever sent to these.
     * @param authHeader read per request: the `Authorization` value, or null to send none.
     */
    fun config(addresses: Collection<ServerAddress?>, draft: ServerNetDraft, authHeader: () -> String?) =
        ServerNetConfig(
            authorities = addresses.filterNotNull().map { it.authority }.toSet(),
            allowCleartext = draft.allowCleartext,
            pinnedSpkiSha256 = draft.pinnedSpkiSha256,
            clientCertAlias = draft.clientCertAlias,
            customHeaders = draft.customHeaders,
            authHeader = authHeader,
        )

    /** @throws AppError `TLS_UNTRUSTED` if the saved pin was damaged (see [Server.pinInvalid]). */
    fun config(addresses: Collection<ServerAddress?>, server: Server, authHeader: () -> String?): ServerNetConfig {
        if (server.pinInvalid) throw AppError.of(AppError.Kind.TLS_UNTRUSTED)
        return config(
            addresses,
            ServerNetDraft(server.allowCleartext, server.pinnedSpkiSha256, server.clientCertAlias, server.customHeaders),
            authHeader,
        )
    }
}
