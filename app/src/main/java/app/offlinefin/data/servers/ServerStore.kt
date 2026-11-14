package app.offlinefin.data.servers

import androidx.room.withTransaction
import app.offlinefin.core.AppError
import app.offlinefin.data.db.AccountEntity
import app.offlinefin.data.db.ActiveAccountEntity
import app.offlinefin.data.db.AppDatabase
import app.offlinefin.data.db.ServerEntity
import app.offlinefin.net.ServerAddress
import app.offlinefin.net.isValidPin
import app.offlinefin.security.SecretBox
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

/**
 * Servers and accounts. Access tokens and custom headers reach the database only as [SecretBox]
 * ciphertext. Anything unreadable (lost key, damaged row) degrades instead of crashing: an account whose
 * token cannot be opened is hidden (the user signs in again), headers that cannot be opened load as none,
 * and a damaged certificate pin is surfaced as [Server.pinInvalid].
 */
class ServerStore(
    private val db: AppDatabase,
    private val box: SecretBox,
    /** Called after anything that makes cached API clients stale (account or server removed, settings saved). */
    private val onClientsStale: (serverId: String, addresses: List<ServerAddress>) -> Unit = { _, _ -> },
) {
    private val serverDao = db.serverDao()
    private val accountDao = db.accountDao()

    val servers: Flow<List<Server>> = serverDao.observeAll().map { rows -> rows.map(::toServer) }

    /** `serverId:userId` to token, for [cachedToken]. Filled on every account write and read, emptied on removal. */
    private val tokens = ConcurrentHashMap<String, String>()

    /**
     * The live token lookup handed to `ServerNet.authenticated`: no database or decryption per request. Null
     * when the account is signed out or has not been loaded yet (reading it through [accounts] / [active]
     * loads it; anything that has an account to authenticate as has read it).
     */
    fun cachedToken(serverId: String, userId: String): String? = tokens[tokenKey(serverId, userId)]

    val activeAccount: Flow<Account?> = accountDao.observeActive().map { it?.let(::toAccount) }

    fun accounts(serverId: String): Flow<List<Account>> =
        accountDao.observeForServer(serverId).map { rows -> rows.mapNotNull(::toAccount) }

    /** @throws IllegalArgumentException if the pin is not a valid base64 SHA-256 value. */
    suspend fun upsertServer(s: Server) {
        require(s.pinnedSpkiSha256 == null || isValidPin(s.pinnedSpkiSha256)) { "The certificate pin is not valid." }
        val before = serverDao.get(s.id)?.let(::toServer)
        db.withTransaction {
            // A server loaded with a damaged pin keeps that column as it is until the user re-trusts it.
            val pin = if (s.pinInvalid && s.pinnedSpkiSha256 == null) serverDao.get(s.id)?.pinnedSpkiSha256 else s.pinnedSpkiSha256
            serverDao.upsert(
                ServerEntity(
                    id = s.id, name = s.name, version = s.version,
                    homeAddress = s.homeAddress?.value, awayAddress = s.awayAddress?.value,
                    allowCleartext = s.allowCleartext, pinnedSpkiSha256 = pin, clientCertAlias = s.clientCertAlias,
                    customHeaders = box.seal(Json.encodeToString(HEADERS, s.customHeaders)),
                ),
            )
        }
        onClientsStale(s.id, (before?.addresses.orEmpty() + s.addresses).distinct())
    }

    /** Also removes the server's accounts. */
    suspend fun removeServer(id: String) {
        val addresses = server(id)?.addresses.orEmpty()
        try {
            serverDao.delete(id)
        } finally {
            tokens.keys.removeAll { it.startsWith("$id:") }
            onClientsStale(id, addresses)
        }
    }

    /** The server must already be saved. */
    suspend fun upsertAccount(a: Account, makeActive: Boolean) {
        val addresses = server(a.serverId)?.addresses.orEmpty()
        db.withTransaction {
            accountDao.upsert(AccountEntity(a.serverId, a.userId, a.userName, box.seal(a.accessToken)))
            if (makeActive) accountDao.setActive(ActiveAccountEntity(0, a.serverId, a.userId))
        }
        tokens[tokenKey(a.serverId, a.userId)] = a.accessToken
        onClientsStale(a.serverId, addresses)
    }

    /** If it was the active account, nobody is active afterwards. */
    suspend fun removeAccount(serverId: String, userId: String) {
        val addresses = server(serverId)?.addresses.orEmpty()
        try {
            accountDao.delete(serverId, userId)
        } finally {
            // Whether or not the delete worked, the account stops sending its token and its clients go.
            tokens.remove(tokenKey(serverId, userId))
            onClientsStale(serverId, addresses)
        }
    }

    /** @throws IllegalArgumentException if there is no such account. */
    suspend fun setActive(serverId: String, userId: String) {
        db.withTransaction {
            requireNotNull(accountDao.get(serverId, userId)) { "No such account." }
            accountDao.setActive(ActiveAccountEntity(0, serverId, userId))
        }
    }

    suspend fun server(id: String): Server? = serverDao.get(id)?.let(::toServer)

    suspend fun active(): Pair<Server, Account>? {
        val account = accountDao.getActive()?.let(::toAccount) ?: return null
        val server = server(account.serverId) ?: return null
        return server to account
    }

    private fun toAccount(e: AccountEntity): Account? = try {
        Account(e.serverId, e.userId, e.userName, box.open(e.accessToken)).also {
            // putIfAbsent: a read never overwrites a newer token written by a sign-in.
            tokens.putIfAbsent(tokenKey(e.serverId, e.userId), it.accessToken)
        }
    } catch (_: AppError) {
        null
    }

    private fun tokenKey(serverId: String, userId: String) = "$serverId:$userId"

    private fun toServer(e: ServerEntity): Server {
        val pinOk = e.pinnedSpkiSha256 == null || isValidPin(e.pinnedSpkiSha256)
        val headers = try {
            Json.decodeFromString(HEADERS, box.open(e.customHeaders))
        } catch (_: Exception) {
            emptyMap()
        }
        return Server(
            id = e.id, name = e.name, version = e.version,
            homeAddress = e.homeAddress?.let(::parseOrNull), awayAddress = e.awayAddress?.let(::parseOrNull),
            allowCleartext = e.allowCleartext,
            pinnedSpkiSha256 = e.pinnedSpkiSha256.takeIf { pinOk },
            clientCertAlias = e.clientCertAlias,
            customHeaders = headers,
            pinInvalid = !pinOk,
        )
    }

    private fun parseOrNull(value: String): ServerAddress? = try {
        ServerAddress.parse(value)
    } catch (_: AppError) {
        null
    }

    private companion object {
        val HEADERS = MapSerializer(String.serializer(), String.serializer())
    }
}
