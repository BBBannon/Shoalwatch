package app.shoalwatch.data.servers

import app.shoalwatch.net.ServerAddress
import app.shoalwatch.net.ServerNetConfig

/** What `/System/Info/Public` told us about a server. */
data class ServerInfo(
    val id: String,
    val name: String,
    val version: String,
    val address: ServerAddress,
    val compatibility: Compatibility,
) {
    enum class Compatibility {
        /** Jellyfin 10.10 to 12.x: the endpoints used here are identical across that range. */
        SUPPORTED,

        /** Older than 10.10: may work, not supported. */
        TOO_OLD,

        /** A newer major line than any checked (13+). */
        NEWER_UNTESTED,

        /** No parseable version reported. */
        UNKNOWN,
    }

    companion object {
        fun compatibilityOf(version: String): Compatibility {
            val parts = version.split('.').map { it.toIntOrNull() }
            val major = parts.getOrNull(0) ?: return Compatibility.UNKNOWN
            val minor = parts.getOrNull(1) ?: return Compatibility.UNKNOWN
            return when {
                major < 10 || (major == 10 && minor < 10) -> Compatibility.TOO_OLD
                major <= 12 -> Compatibility.SUPPORTED
                else -> Compatibility.NEWER_UNTESTED
            }
        }
    }
}

/** A pending Quick Connect request: [code] is shown to the user, [secret] (a credential) is used to poll. */
data class QcSession(val secret: String, val code: String) {
    override fun toString() = "QcSession(<redacted>)"
}

/** A successful sign-in. [accessToken] is a credential. */
data class AuthResult(val userId: String, val userName: String, val accessToken: String, val serverId: String) {
    override fun toString() = "AuthResult($userName)"
}

/**
 * The only code that speaks the Jellyfin HTTP API for sign-in. Every call goes through a client built by
 * `HttpClientFactory` from the given [ServerNetConfig], so the trust policy and request guards always apply.
 * Callers should pass the same config instance for related calls (clients are cached per config).
 * All failures are thrown as `AppError`.
 */
interface JellyfinGateway {
    /** `GET /System/Info/Public` */
    suspend fun publicInfo(address: ServerAddress, net: ServerNetConfig): ServerInfo

    /** `GET /QuickConnect/Enabled` */
    suspend fun quickConnectEnabled(address: ServerAddress, net: ServerNetConfig): Boolean

    /** `POST /QuickConnect/Initiate` */
    suspend fun quickConnectInitiate(address: ServerAddress, net: ServerNetConfig): QcSession

    /** `GET /QuickConnect/Connect?secret=` - true once the user has approved the code. */
    suspend fun quickConnectState(address: ServerAddress, net: ServerNetConfig, secret: String): Boolean

    /** `POST /Users/AuthenticateWithQuickConnect` */
    suspend fun authenticateWithQuickConnect(address: ServerAddress, net: ServerNetConfig, secret: String): AuthResult

    /** `POST /Users/AuthenticateByName` */
    suspend fun authenticateByName(address: ServerAddress, net: ServerNetConfig, username: String, password: String): AuthResult

    /** `POST /Sessions/Logout`: revokes the token in [net]'s `authHeader`. */
    suspend fun logout(address: ServerAddress, net: ServerNetConfig)
}
