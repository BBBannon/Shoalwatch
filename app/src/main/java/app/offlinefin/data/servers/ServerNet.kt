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

/**
 * The single place where a server's security settings become a client configuration. Anonymous and
 * authenticated configurations have separate entry points, so a token provider can never be paired with a
 * missing [ServerNetConfig.authKey] (which would let one cached client serve two identities).
 */
object ServerNet {
    /**
     * No token is ever sent. Used to find a server, probe an address and sign in.
     *
     * @param addresses the addresses that count as "this server" (nulls are ignored); custom headers are only
     *   ever sent to these.
     */
    fun anonymous(addresses: Collection<ServerAddress?>, draft: ServerNetDraft, device: DeviceIdentity): ServerNetConfig =
        build(addresses, draft, authKey = null) { device.authHeader(null) }

    /** @throws AppError `TLS_UNTRUSTED` if the saved pin was damaged (see [Server.pinInvalid]). */
    fun anonymous(addresses: Collection<ServerAddress?>, server: Server, device: DeviceIdentity): ServerNetConfig =
        anonymous(addresses, draftOf(server), device)

    /**
     * Sends [userId]'s token (the login token goes only to [addresses]). [tokens] is read on every request
     * (`(serverId, userId) -> token`), so a re-sign-in is picked up and a signed-out account sends none, even
     * through a cached client, which keeps the first configuration's provider for this `serverId:userId`.
     * So pass one long-lived lookup (see `ServerStore.cachedToken`), never a captured string.
     *
     * @throws AppError `TLS_UNTRUSTED` if the saved pin was damaged (see [Server.pinInvalid]).
     */
    fun authenticated(
        addresses: Collection<ServerAddress?>,
        server: Server,
        userId: String,
        device: DeviceIdentity,
        tokens: (serverId: String, userId: String) -> String?,
    ): ServerNetConfig =
        build(addresses, draftOf(server), authKey = "${server.id}:$userId") { device.authHeader(tokens(server.id, userId)) }

    private fun draftOf(server: Server): ServerNetDraft {
        if (server.pinInvalid) throw AppError.of(AppError.Kind.TLS_UNTRUSTED)
        return ServerNetDraft(server.allowCleartext, server.pinnedSpkiSha256, server.clientCertAlias, server.customHeaders)
    }

    private fun build(addresses: Collection<ServerAddress?>, draft: ServerNetDraft, authKey: String?, authHeader: () -> String?) =
        ServerNetConfig(
            authorities = addresses.filterNotNull().map { it.authority }.toSet(),
            allowCleartext = draft.allowCleartext,
            pinnedSpkiSha256 = draft.pinnedSpkiSha256,
            clientCertAlias = draft.clientCertAlias,
            customHeaders = draft.customHeaders,
            authKey = authKey,
            authHeader = authHeader,
        )
}
