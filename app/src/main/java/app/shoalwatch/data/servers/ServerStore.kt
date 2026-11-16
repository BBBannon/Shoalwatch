package app.shoalwatch.data.servers

import androidx.room.withTransaction
import app.shoalwatch.core.AppError
import app.shoalwatch.core.AppLog
import app.shoalwatch.data.db.AccountEntity
import app.shoalwatch.data.db.AccountRow
import app.shoalwatch.data.db.ActiveAccountEntity
import app.shoalwatch.data.db.AppDatabase
import app.shoalwatch.data.db.ServerEntity
import app.shoalwatch.net.ServerAddress
import app.shoalwatch.net.isValidPin
import app.shoalwatch.security.SecretBox
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Servers and accounts. Access tokens and custom headers reach the database only as [SecretBox]
 * ciphertext. Anything unreadable (lost key, damaged row) degrades instead of crashing: an account whose
 * token cannot be opened is hidden (the user signs in again), headers that cannot be opened load as none,
 * and a damaged certificate pin is surfaced as [Server.pinInvalid].
 *
 * Account lists and the active account wait for the first [warmTokens] and show only accounts whose token is loaded
 * (see [usable]), so what is listed always agrees with what can send a request (review F10).
 *
 * Keystore use (review I7): what screens read ([summaries], [accounts], [activeAccount], [active]) selects only
 * non-secret columns and never touches [box]. Every seal and open runs on [io], never on the caller's thread:
 * [warmTokens], [upsertAccount], [upsertServer], the revocation reads [token]/[tokens], and full server reads
 * ([server], [servers], [observeServer]) which open the custom headers.
 */
class ServerStore(
    private val db: AppDatabase,
    private val box: SecretBox,
    /** Called after anything that makes cached API clients stale (account or server removed, settings saved). */
    private val onClientsStale: (serverId: String, addresses: List<ServerAddress>) -> Unit = { _, _ -> },
    /** Called after a server is removed, or saved with no address left to probe, so per-server state elsewhere (e.g. address probing) can be dropped. */
    private val onServerRemoved: (serverId: String) -> Unit = {},
    /** Called after an account is removed or signed out (its token rejected), so per-account state elsewhere (e.g. remembered library failures) can be dropped. */
    private val onAccountGone: (serverId: String, userId: String) -> Unit = { _, _ -> },
    /** Called after an account is saved (signed in, or signed in again), so remembered failures of its earlier sign-in can be dropped. */
    private val onAccountSignedIn: (serverId: String, userId: String) -> Unit = { _, _ -> },
    /** Where [warmTokens] reads saved accounts from (the database by default); a test passes one that fails. */
    accountSource: (suspend () -> List<AccountEntity>)? = null,
    /** Where Keystore work runs. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ServerSettingsSource {
    private val serverDao = db.serverDao()
    private val accountDao = db.accountDao()

    /** Guards every read the screens observe ([summaries], [activeAccount], [accounts], [observeServer]); see [ReadHealth]. */
    private val reads = ReadHealth(TAG)

    /** True while a read the screens observe has failed; see `ServerCatalog.readFailed`. */
    val readFailed: StateFlow<Boolean> = reads.failed

    /** Opened custom headers, memoised by ciphertext (see [OpenedHeaders]). */
    private val openedHeaders = OpenedHeaders(box)

    /** Every server with its full settings (headers opened on [io]). For the address selector, not for lists. */
    override val servers: Flow<List<Server>> = serverDao.observeAll().map { rows -> rows.map(::toServer) }.flowOn(io)

    /** What a list of servers shows. No secret column is read. */
    val summaries: Flow<List<ServerSummary>> = reads.guard("servers", emptyList()) {
        serverDao.observeRows().map { rows ->
            rows.map { ServerSummary(it.id, it.name, it.version, pinInvalid = it.pinnedSpkiSha256 != null && !isValidPin(it.pinnedSpkiSha256)) }
        }
    }

    /** One server with its full settings, live (for its settings screen); null once removed, or while it cannot be read. */
    fun observeServer(id: String): Flow<Server?> = reads.guard("a server", null) { servers.map { list -> list.firstOrNull { it.id == id } } }

    /** `(serverId, userId)` to token, for [cachedToken]. Changed only by [upsertAccount], [warmTokens] and removals, all under [tokenLock]. */
    private val tokens = ConcurrentHashMap<Pair<String, String>, String>()
    private val tokenLock = Mutex()

    /** Accounts whose token the server rejected: [warmTokens] skips them until a new sign-in. Changed under [tokenLock]. */
    private val rejected = ConcurrentHashMap.newKeySet<Pair<String, String>>()
    private val generations = ConcurrentHashMap<Pair<String, String>, Long>()

    /**
     * The accounts whose token is in [tokens] (the same keys, observable): the only ones any list or [active] shows.
     * So an account whose token cannot be opened (lost key), or that a failed warm never loaded, is hidden
     * consistently, exactly as a request for it would be refused (`NOT_SIGNED_IN`). Changed under [tokenLock].
     */
    private val usable = MutableStateFlow<Set<Pair<String, String>>>(emptySet())
    /** Accounts whose token is loaded now (what can send). Not secret. */
    val usableAccounts: StateFlow<Set<Pair<String, String>>> = usable

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
     * finished first is simply absent. Only accounts whose token could be opened become visible in the lists.
     *
     * Never throws (except for cancellation): a database or Keystore failure is logged by kind only and
     * leaves the map as it was, so authenticated requests are refused as signed out. [awaitWarmed] completes
     * either way.
     */
    suspend fun warmTokens() {
        try {
            withContext(io) {
                tokenLock.withLock {
                    val rows = loadAccounts()
                    val opened = rows.map { (it.serverId to it.userId) to toAccount(it)?.accessToken }
                    val loaded = opened.mapNotNull { (key, token) -> token?.takeIf { key !in rejected }?.let { key to it } }.toMap()
                    tokens.keys.retainAll(loaded.keys)
                    tokens.putAll(loaded)
                    usable.value = loaded.keys.toSet()
                }
            }
            warmFailed = false
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive() // a real cancellation must propagate
            warmFailed = true
            AppLog.w(TAG, "Loading saved sign-ins failed", e)
        }
        warmed.complete(Unit)
    }

    /**
     * Tries the warm once more if the last one failed (used by a "Retry" action); still fails closed if it fails again. Also
     * subscribes again every observed read that failed ([readFailed]).
     */
    suspend fun retryWarmIfFailed(): Boolean {
        reads.retry()
        if (warmFailed) warmTokens()
        return warmFailed
    }

    private val warmed = CompletableDeferred<Unit>()

    @Volatile private var warmFailed = false

    private val loadAccounts: suspend () -> List<AccountEntity> = accountSource ?: { accountDao.getAllUsable() } // a rejected sign-in is not loaded again (I4)

    /**
     * Completes when the first [warmTokens] has finished, successfully or not (a failed warm leaves the live
     * lookup empty, so requests fail closed as `NOT_SIGNED_IN`). Start-up routing waits on this before it
     * decides which screen to show.
     */
    suspend fun awaitWarmed() = warmed.await()

    /** [active] after [awaitWarmed]: the one call to use before an authenticated request, so a cold start never sees "signed out". */
    suspend fun awaitActive(): AccountSummary? {
        awaitWarmed()
        return active()
    }

    /** Who is signed in, without the token. */
    val activeAccount: Flow<AccountSummary?> = reads.guard("the active account", null) {
        flow {
            awaitWarmed()
            emitAll(combine(accountDao.observeActiveRow(), usable) { row, ok -> row?.takeIf { it.key() in ok }?.summary() })
        }
    }

    /** A server's accounts that can be used, without tokens; with [expired], those whose sign-in was rejected (kept for their changes). */
    fun accounts(serverId: String, expired: Boolean = false): Flow<List<AccountSummary>> = reads.guard("accounts", emptyList()) {
        flow {
            awaitWarmed()
            emitAll(combine(accountDao.observeRowsForServer(serverId), usable) { rows, ok -> rows.filter { (it.key() in ok) != expired }.map { it.summary() } })
        }
    }

    /** @throws IllegalArgumentException if the id is not a normalised [JellyfinId], or the pin is not a valid base64 SHA-256 value. */
    suspend fun upsertServer(s: Server) {
        requireNormalised(s.id)
        require(s.pinnedSpkiSha256 == null || isValidPin(s.pinnedSpkiSha256)) { "The certificate pin is not valid." }
        val before = server(s.id)
        val sealedHeaders = withContext(io) { box.seal(Json.encodeToString(HEADERS, s.customHeaders)) }
        db.withTransaction {
            // A server loaded with a damaged pin keeps that column as it is until the user re-trusts it.
            val pin = if (s.pinInvalid && s.pinnedSpkiSha256 == null) serverDao.get(s.id)?.pinnedSpkiSha256 else s.pinnedSpkiSha256
            serverDao.upsert(
                ServerEntity(
                    id = s.id, name = s.name, version = s.version,
                    homeAddress = s.homeAddress?.value, awayAddress = s.awayAddress?.value,
                    allowCleartext = s.allowCleartext, pinnedSpkiSha256 = pin, clientCertAlias = s.clientCertAlias,
                    customHeaders = sealedHeaders,
                ),
            )
        }
        openedHeaders.forget()
        onClientsStale(s.id, (before?.addresses.orEmpty() + s.addresses).distinct())
        if (s.addresses.isEmpty()) onServerRemoved(s.id)
    }

    /**
     * Also removes the server's accounts. The removal runs to completion even if the caller is cancelled
     * (while waiting for the lock or during the delete): a sign-out must never leave a token live.
     * Revoking the sign-ins on the server first is `AuthRepository.removeServer`'s job.
     */
    suspend fun removeServer(id: String) = withContext(NonCancellable) {
        val addresses = addressesOf(id)
        try {
            tokenLock.withLock {
                try {
                    serverDao.delete(id)
                } finally {
                    tokens.keys.removeAll { it.first == id }
                    rejected.removeAll { it.first == id }
                    usable.update { set -> set.filterNot { it.first == id }.toSet() }
                    openedHeaders.forget()
                }
            }
        } finally {
            onClientsStale(id, addresses)
            onServerRemoved(id)
        }
    }

    /**
     * The server must already be saved.
     *
     * @throws IllegalArgumentException if the server or user id is not a normalised [JellyfinId].
     */
    suspend fun upsertAccount(a: Account, makeActive: Boolean) {
        requireNormalised(a.serverId)
        requireNormalised(a.userId)
        val addresses = addressesOf(a.serverId)
        val sealed = withContext(io) { box.seal(a.accessToken) }
        tokenLock.withLock {
            db.withTransaction {
                accountDao.upsert(AccountEntity(a.serverId, a.userId, a.userName, sealed))
                accountDao.clearRejected(a.serverId, a.userId)
                if (makeActive) accountDao.setActive(ActiveAccountEntity(0, a.serverId, a.userId))
            }
            tokens[a.serverId to a.userId] = a.accessToken
            rejected -= (a.serverId to a.userId)
            bump(a.serverId to a.userId)
            usable.update { it + (a.serverId to a.userId) }
        }
        onClientsStale(a.serverId, addresses)
        onAccountSignedIn(a.serverId, a.userId)
    }

    /** If it was the active account, nobody is active afterwards. Runs to completion even if the caller is cancelled (see [removeServer]). */
    suspend fun removeAccount(serverId: String, userId: String) = withContext(NonCancellable) {
        val addresses = addressesOf(serverId)
        try {
            tokenLock.withLock {
                try {
                    accountDao.delete(serverId, userId)
                } finally {
                    // Whether or not the delete worked, the account stops sending its token.
                    tokens.remove(serverId to userId)
                    rejected -= (serverId to userId)
                    bump(serverId to userId)
                    usable.update { it - (serverId to userId) }
                }
            }
        } finally {
            onClientsStale(serverId, addresses)
            onAccountGone(serverId, userId)
        }
    }

    /**
     * Changes every time the account's sign-in is replaced, rejected or removed. A request notes it before sending so a
     * late 401 for an older sign-in can be told apart from one for the current token (see [invalidateToken]). Not secret.
     */
    fun tokenGeneration(serverId: String, userId: String): Long = generations[serverId to userId] ?: 0

    /**
     * The server rejected this account's token (401): stop using it. The in-memory token goes, the account becomes unusable
     * (hidden from [accounts], [activeAccount] and [active], like any account without a loaded token), cached clients for
     * the server are dropped, and [warmTokens] will not load it back, after a restart too (`rejected_sign_ins`). The account row stays
     * (its outbox rows wait): signing in again overwrites it and clears the rejection.
     * With [ifGeneration], nothing happens unless that is still the current [tokenGeneration], so a rejection of a sign-in
     * that has since been replaced does not sign the new one out. Runs to completion even if the caller is cancelled.
     */
    suspend fun invalidateToken(serverId: String, userId: String, ifGeneration: Long? = null) = withContext(NonCancellable) {
        val addresses = addressesOf(serverId)
        val key = serverId to userId
        val done = tokenLock.withLock {
            if (ifGeneration != null && tokenGeneration(serverId, userId) != ifGeneration) return@withLock false
            rejected += key
            // Kept across a restart (I4); if it cannot be written, the set above still holds it for this process.
            try { accountDao.reject(serverId, userId, System.currentTimeMillis()) } catch (e: Exception) { AppLog.w(TAG, "Saving a rejected sign-in failed", e) }
            tokens.remove(key)
            usable.update { it - key }
            bump(key)
            true
        }
        if (done) {
            onClientsStale(serverId, addresses)
            onAccountGone(serverId, userId)
        }
    }

    private fun bump(key: Pair<String, String>) { generations.merge(key, 1L, Long::plus) }

    /** @throws IllegalArgumentException if there is no such account. */
    suspend fun setActive(serverId: String, userId: String) {
        db.withTransaction {
            requireNotNull(accountDao.get(serverId, userId)) { "No such account." }
            accountDao.setActive(ActiveAccountEntity(0, serverId, userId))
        }
    }

    /** The server with its full settings (headers opened on [io]). */
    override suspend fun server(id: String): Server? = withContext(io) { serverDao.get(id)?.let(::toServer) }

    /**
     * One account's saved token (opened on [io]), for revoking it. [StoredToken.Unreadable] (lost key, damaged
     * ciphertext) is deliberately not the same as [StoredToken.Missing]: "nothing to revoke" and "cannot revoke" differ.
     */
    suspend fun token(serverId: String, userId: String): StoredToken = withContext(io) {
        val row = accountDao.get(serverId, userId) ?: return@withContext StoredToken.Missing
        toAccount(row)?.let { StoredToken.Found(it.accessToken) } ?: StoredToken.Unreadable
    }

    /** Every account saved for [serverId] (opened on [io]): the readable `(userId, token)` pairs and how many could not be read. */
    suspend fun tokens(serverId: String): StoredTokens = withContext(io) {
        val opened = accountDao.getForServer(serverId).map { it to toAccount(it) }
        StoredTokens(opened.mapNotNull { (_, a) -> a?.let { it.userId to it.accessToken } }, unreadable = opened.count { it.second == null })
    }

    /** Who is signed in, without the token (accounts cascade with their server, so its server exists). */
    suspend fun active(): AccountSummary? {
        awaitWarmed()
        return accountDao.getActiveRow()?.takeIf { it.key() in usable.value }?.summary()
    }

    /** Addresses only (no secrets opened), for client eviction. */
    private suspend fun addressesOf(id: String): List<ServerAddress> =
        serverDao.get(id)?.let { e -> listOfNotNull(e.homeAddress?.let(::parseOrNull), e.awayAddress?.let(::parseOrNull)) }.orEmpty()

    /** Ids become keys, route segments and (Milestone 4) file names: only the gateway's normalised form is stored. The id is never echoed. */
    private fun requireNormalised(id: String) =
        require(JellyfinId.parseOrNull(id)?.value == id) { "An id must be a normalised Jellyfin id." }

    private fun AccountRow.key() = serverId to userId
    private fun AccountRow.summary() = AccountSummary(serverId, userId, userName)

    private fun toAccount(e: AccountEntity): Account? = try {
        Account(e.serverId, e.userId, e.userName, box.open(e.accessToken))
    } catch (_: AppError) {
        null
    }

    private fun toServer(e: ServerEntity): Server {
        val pinOk = e.pinnedSpkiSha256 == null || isValidPin(e.pinnedSpkiSha256)
        val headers = openedHeaders.open(e.customHeaders)
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
        val HEADERS = OpenedHeaders.HEADERS
    }
}
