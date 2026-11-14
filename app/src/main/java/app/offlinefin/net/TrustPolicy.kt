package app.offlinefin.net

/**
 * Everything the network layer needs to know about one server.
 *
 * @property authorities exact lower-case `host:port` strings (see [ServerAddress.authority]) that
 *   count as "this server": the login token and custom headers are only ever sent to these.
 * @property allowCleartext whether plain `http://` is permitted for this server (opt-in, per server).
 * @property pinnedSpkiSha256 base64 SHA-256 of the certificate's public key the user chose to trust
 *   (for self-signed servers), or null to rely on the system's trusted authorities alone.
 * @property clientCertAlias `KeyChain` alias of a client certificate to present, or null.
 * @property authHeader read for every request; the current `Authorization` value, or null when signed out.
 */
data class ServerNetConfig(
    val authorities: Set<String>,
    val allowCleartext: Boolean,
    val pinnedSpkiSha256: String?,
    val clientCertAlias: String?,
    val customHeaders: Map<String, String>,
    val authHeader: () -> String?,
) {
    /** Never prints headers or the token. */
    override fun toString() = "ServerNetConfig(<redacted>)"
}
