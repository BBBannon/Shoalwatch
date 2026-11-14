package app.offlinefin.data.servers

import androidx.room.withTransaction
import app.offlinefin.core.AppError
import app.offlinefin.core.AppLog
import app.offlinefin.data.db.AccountEntity
import app.offlinefin.data.db.ActiveAccountEntity
import app.offlinefin.data.db.AppDatabase
import app.offlinefin.data.db.ServerEntity
import app.offlinefin.net.ServerAddress
import app.offlinefin.net.isValidPin
import app.offlinefin.security.SecretBox
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
    /** Called after a server is removed, or saved with no address left to probe, so per-server state elsewhere (e.g. address probing) can be dropped. */
    private val onServerRemoved: (serverId: String) -> Unit = {},
) : ServerCatalog {
    private val serverDao = db.serverDao()
    private val accountDao = db.accountDao()

    override val servers: Flow<List<Server>> = serverDao.observeAll().map { rows -> rows.map(::toServer) }

    /** `(serverId, userId)` to token, for [cachedToken]. Changed only by [upsertAccount], [warmTokens] and removals, all under [tokenLock]. */
    private val tokens = ConcurrentHashMap<Pair<String, String>, String>()
    private val tokenLock = Mutex()

    /**
     * The live token lookup handed to `ServerNet.authenticated`: no database or decryption per request. Null
     * when the account is signed out or [warmTokens] has not loaded it yet; an authenticated request with no
     * token is refused (`NOT_SIGNED_IN`), never sent anonymously. Reading rows never fills this map, so a
     * stale read can not bring a removed account's token back.
     */
    fun cachedToken(serverId: String, userId: String): String? = tokens[serverId to userId]

    /**
     * Loads every saved account's token. Call once at app start, before any authenticated request; safe to
     * repeat. Serialised with sign-in and removal and re-reads the rows inside the lock, so a removal that
     * finished first is simply absent.
     *
     * Never throws (except for cancellation): a database or Keystore failure is logged by kind only and
     * leaves the map as it was, so authenticated requests are refused as signed out. [awaitWarmed] completes
     * either way.
     */
    suspend fun warmTokens() {
        try {
            tokenLock.withLock {
                val loaded = accountDao.getAll().mapNotNull(::toAccount).associate { (it.serverId to it.userId) to it.accessToken }
                tokens.keys.retainAll(loaded.keys)
                tokens.putAll(loaded)
            }
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive() // a real cancellation must propagate
            AppLog.w(TAG, "Loading saved sign-ins failed", e)
        }
        warmed.complete(Unit)
    }

    private val warmed = CompletableDeferred<Unit>()

    /**
     * Completes when the first [warmTokens] has finished, successfully or not (a failed warm leaves the live
     * lookup empty, so requests fail closed as `NOT_SIGNED_IN`). Start-up routing waits on this before it
     * decides which screen to show.
     */
    override suspend fun awaitWarmed() = warmed.await()

    /** [active] after [awaitWarmed]: the one call to use before an authenticated request, so a cold start never sees "signed out". */
    override suspend fun awaitActive(): Pair<Server, Account>? {
        awaitWarmed()
        return active()
    }

    override val activeAccount: Flow<Account?> = accountDao.observeActive().map { it?.let(::toAccount) }

    override fun accounts(serverId: String): Flow<List<Account>> =
        accountDao.observeForServer(serverId).map { rows -> rows.mapNotNull(::toAccount) }

    /** @throws IllegalArgumentException if the pin is not a valid base64 SHA-256 value. */
    override suspend fun upsertServer(s: Server) {
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
        if (s.addresses.isEmpty()) onServerRemoved(s.id)
    }

    /**
     * Also removes the server's accounts. The removal runs to completion even if the caller is cancelled
     * (while waiting for the lock or during the delete): a sign-out must never leave a token live.
     */
    override suspend fun removeServer(id: String) = withContext(NonCancellable) {
        val addresses = server(id)?.addresses.orEmpty()
        try {
            tokenLock.withLock {
                try {
                    serverDao.delete(id)
                } finally {
                    tokens.keys.removeAll { it.first == id }
                }
            }
        } finally {
            onClientsStale(id, addresses)
            onServerRemoved(id)
        }
    }

    /** The server must already be saved. */
    suspend fun upsertAccount(a: Account, makeActive: Boolean) {
        val addresses = server(a.serverId)?.addresses.orEmpty()
        tokenLock.withLock {
            db.withTransaction {
                accountDao.upsert(AccountEntity(a.serverId, a.userId, a.userName, box.seal(a.accessToken)))
                if (makeActive) accountDao.setActive(ActiveAccountEntity(0, a.serverId, a.userId))
            }
            tokens[a.serverId to a.userId] = a.accessToken
        }
        onClientsStale(a.serverId, addresses)
    }

    /** If it was the active account, nobody is active afterwards. Runs to completion even if the caller is cancelled (see [removeServer]). */
    suspend fun removeAccount(serverId: String, userId: String) = withContext(NonCancellable) {
        val addresses = server(serverId)?.addresses.orEmpty()
        try {
            tokenLock.withLock {
                try {
                    accountDao.delete(serverId, userId)
                } finally {
                    // Whether or not the delete worked, the account stops sending its token.
                    tokens.remove(serverId to userId)
                }
            }
        } finally {
            onClientsStale(serverId, addresses)
        }
    }

    /** @throws IllegalArgumentException if there is no such account. */
    override suspend fun setActive(serverId: String, userId: String) {
        db.withTransaction {
            requireNotNull(accountDao.get(serverId, userId)) { "No such account." }
            accountDao.setActive(ActiveAccountEntity(0, serverId, userId))
        }
    }

    override suspend fun server(id: String): Server? = serverDao.get(id)?.let(::toServer)

    suspend fun active(): Pair<Server, Account>? {
        val account = accountDao.getActive()?.let(::toAccount) ?: return null
        val server = server(account.serverId) ?: return null
        return server to account
    }

    private fun toAccount(e: AccountEntity): Account? = try {
        Account(e.serverId, e.userId, e.userName, box.open(e.accessToken))
    } catch (_: AppError) {
        null
    }


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
        const val TAG = "ServerStore"
        val HEADERS = MapSerializer(String.serializer(), String.serializer())
    }
}
