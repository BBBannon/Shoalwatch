package app.shoalwatch.net

/**
 * Everything the network layer needs to know about one server.
 *
 * @property authorities exact lower-case `host:port` strings (see [ServerAddress.authority]) that
 *   count as "this server": the login token and custom headers are only ever sent to these.
 * @property allowCleartext whether plain `http://` is permitted for this server (opt-in, per server).
 * @property pinnedSpkiSha256 base64 SHA-256 of the certificate's public key the user chose to trust
 *   (for self-signed servers), or null to rely on the system's trusted authorities alone.
 * @property clientCertAlias `KeyChain` alias of a client certificate to present, or null.
 * @property authKey identifies whose token [authHeader] supplies (null = anonymous, otherwise `serverId:userId`). Together
 *   with the other value fields it is the identity of a configuration (see `ServerClients`).
 * @property authHeader read for every request; the current `Authorization` value, or null when signed out.
 */
class ServerNetConfig(
    val authorities: Set<String>,
    val allowCleartext: Boolean,
    val pinnedSpkiSha256: String?,
    val clientCertAlias: String?,
    val customHeaders: Map<String, String>,
    val authKey: String? = null,
    val authHeader: () -> String?,
) {
    /** Equality is by value: the security fields and [authKey]; the [authHeader] lambda is deliberately excluded. */
    override fun equals(other: Any?) = other is ServerNetConfig &&
        authorities == other.authorities && allowCleartext == other.allowCleartext &&
        pinnedSpkiSha256 == other.pinnedSpkiSha256 && clientCertAlias == other.clientCertAlias &&
        customHeaders == other.customHeaders && authKey == other.authKey

    override fun hashCode() = listOf(authorities, allowCleartext, pinnedSpkiSha256, clientCertAlias, customHeaders, authKey).hashCode()

    /** Never prints headers or the token. */
    override fun toString() = "ServerNetConfig(<redacted>)"
}
