package app.shoalwatch.ui.admin

import app.shoalwatch.core.AppError
import app.shoalwatch.data.admin.AdminRights
import app.shoalwatch.data.admin.AdminState
import app.shoalwatch.data.admin.AdminStatus
import app.shoalwatch.data.servers.Connection
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface AdminGate {
    data object Checking : AdminGate
    data class Open(val session: Session, val online: Boolean) : AdminGate {
        override fun toString() = "Open(<redacted>)"
    }
    /** For good: a closed gate never opens again in this ViewModel. */
    data object Closed : AdminGate
}

enum class AdminProblem { OFFLINE, NETWORK, REFUSED, GONE, SERVER, CONNECTION, OTHER }

/**
 * The session rule for one admin screen (see "Server admin" in docs/ARCHITECTURE.md). The screen belongs to the session that
 * was active when it opened (the owner), and only while [rights] confirms that exact session as an administrator. The gate
 * closes for good — never to reopen in this ViewModel — the moment the active session is anything else (even the owner again
 * later), the rights stop saying (owner, ADMIN), or a failure shows the sign-in or the rights are gone ([problemFor]). While the
 * screen is visible, open and online the rights are re-checked every [recheckMs].
 */
class AdminGuard(
    catalog: ServerCatalog,
    private val rights: AdminRights,
    connections: ServerConnections,
    private val scope: CoroutineScope,
    private val recheckMs: Long = RECHECK_MS,
) {
    private val gateState = MutableStateFlow<AdminGate>(AdminGate.Checking)
    val gate: StateFlow<AdminGate> = gateState.asStateFlow()

    private val visibleState = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = visibleState.asStateFlow()

    /** The session to load as: non-null only while the gate is Open, online, and the screen is visible. Distinct values. */
    val live: Flow<Session?> = combine(gateState, visibleState) { g, v -> (g as? AdminGate.Open)?.takeIf { it.online && v }?.session }
        .distinctUntilChanged()

    private var owner: Session? = null
    /** Everything the guard watches; cancelled when the gate closes (and with [scope]). */
    private val watching = SupervisorJob(scope.coroutineContext[Job])

    init {
        scope.launch(watching) { watch(catalog, connections) }
    }

    private suspend fun watch(catalog: ServerCatalog, connections: ServerConnections) = coroutineScope {
        val first = catalog.activeSession().first()
        if (first == null) {
            close()
            return@coroutineScope
        }
        owner = first
        // Any other active session, now or later, closes the gate; so does the owner coming back after someone else.
        launch { catalog.activeSession().first { it != first }; close() }
        if (rights.check(first) != AdminStatus.ADMIN || gateState.value == AdminGate.Closed) {
            close()
            return@coroutineScope
        }
        val connection = connections.connection(first.serverId)
        gateState.value = AdminGate.Open(first, connection.value is Connection.Online)
        launch { rights.state.first { it != AdminState(first, AdminStatus.ADMIN) }; close() }
        launch {
            connection.collect { c ->
                val open = gateState.value as? AdminGate.Open ?: return@collect
                gateState.value = open.copy(online = c is Connection.Online)
            }
        }
        launch {
            combine(gateState, visibleState) { g, v -> g is AdminGate.Open && g.online && v }.distinctUntilChanged().collectLatest { active ->
                if (!active) return@collectLatest
                while (true) {
                    delay(recheckMs)
                    if (rights.check(first) == AdminStatus.NOT_ADMIN) close()
                }
            }
        }
    }

    fun setVisible(on: Boolean) {
        visibleState.value = on
    }

    /**
     * What a failed admin call means. Null when it closes the gate (UNAUTHORISED, NOT_SIGNED_IN; FORBIDDEN that a fresh
     * `rights.check` turns into NOT_ADMIN — then `rights.lost`), so the caller shows nothing. Otherwise: FORBIDDEN still admin
     * REFUSED, OFFLINE OFFLINE, NETWORK/TIMEOUT NETWORK, SERVER_NOT_FOUND GONE, SERVER_ERROR SERVER, TLS_UNTRUSTED/TLS_PIN_CHANGED/
     * CLEARTEXT_BLOCKED/CLIENT_CERT_REQUIRED CONNECTION, anything else OTHER. Never called with a CancellationException.
     */
    suspend fun problemFor(e: Throwable): AdminProblem? {
        if (gateState.value == AdminGate.Closed) return null
        return when ((e as? AppError)?.kind) {
            AppError.Kind.UNAUTHORISED, AppError.Kind.NOT_SIGNED_IN -> {
                close()
                null
            }
            AppError.Kind.FORBIDDEN -> {
                val who = owner
                if (who != null && rights.check(who) == AdminStatus.ADMIN && gateState.value != AdminGate.Closed) {
                    AdminProblem.REFUSED
                } else {
                    who?.let(rights::lost)
                    close()
                    null
                }
            }
            AppError.Kind.OFFLINE -> AdminProblem.OFFLINE
            AppError.Kind.NETWORK, AppError.Kind.TIMEOUT -> AdminProblem.NETWORK
            AppError.Kind.SERVER_NOT_FOUND -> AdminProblem.GONE
            AppError.Kind.SERVER_ERROR -> AdminProblem.SERVER
            AppError.Kind.TLS_UNTRUSTED, AppError.Kind.TLS_PIN_CHANGED, AppError.Kind.CLEARTEXT_BLOCKED, AppError.Kind.CLIENT_CERT_REQUIRED ->
                AdminProblem.CONNECTION
            else -> AdminProblem.OTHER
        }
    }

    private fun close() {
        gateState.value = AdminGate.Closed
        watching.cancel()
    }

    companion object {
        const val RECHECK_MS = 60_000L
    }
}
