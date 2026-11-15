package app.shoalwatch.data.admin

import app.shoalwatch.core.AppError
import app.shoalwatch.core.AppLog
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

enum class AdminStatus { ADMIN, NOT_ADMIN, UNKNOWN }

data class AdminState(val session: Session, val status: AdminStatus) {
    override fun toString() = "AdminState(<redacted>)"
}

/** Who may see admin screens. Implemented by [AdminAccess]. Holds the active session's answer only, in memory. */
interface AdminRights {
    /** The last answer for the active session, or null (none yet, or the active session changed since). */
    val state: StateFlow<AdminState?>

    /**
     * Asks the server now (GET /Users/Me) as [session]. ADMIN only if the answer's `Id` is this user and `Policy.IsAdministrator`
     * is true and `Policy.IsDisabled` is false. NOT_ADMIN for any other answer, for 401/403/NOT_SIGNED_IN, and for a session that
     * is not the active one (nothing is sent). A failure to reach the server (OFFLINE, NETWORK, TIMEOUT, SERVER_ERROR, TLS) leaves
     * an earlier answer for the same session in place and returns it, or UNKNOWN when there was none. An answer that arrives
     * after the active session changed is dropped and NOT_ADMIN is returned.
     */
    suspend fun check(session: Session): AdminStatus

    /** Records NOT_ADMIN for [session] if it is the one held (a 403 a re-check confirmed). */
    fun lost(session: Session)

    /** True only when [state] is exactly ([session], ADMIN). What `AdminGateway` checks before sending. */
    fun allows(session: Session): Boolean
}

/** GET /Users/Me, the three things the administrator check reads. [userId] is a normalised `JellyfinId`, or null. */
internal data class AdminMe(val userId: String?, val isAdministrator: Boolean, val isDisabled: Boolean) {
    override fun toString() = "AdminMe(<redacted>)"
}

/**
 * [AdminRights] for the app: one `(Session, AdminStatus)` in memory, for the active session only. [sessionChanged] (fed by
 * `ServerCatalog.activeSession()` in `AppGraph`) forgets it on every change, to anyone or nobody, so a profile switch on a
 * shared tablet can never carry another account's administrator status. Nothing is saved. Logs by error kind only.
 */
class AdminAccess internal constructor(private val me: suspend (Session) -> AdminMe) : AdminRights {
    private val held = MutableStateFlow<AdminState?>(null)
    override val state: StateFlow<AdminState?> = held.asStateFlow()

    private val lock = Any()
    private var active: Session? = null
    /** Bumped by every [sessionChanged]: an answer may be stored only if it is unchanged since the check began. */
    private var generation = 0L
    private val firstActive = CompletableDeferred<Unit>()

    /** Fed by `ServerCatalog.activeSession()`; every change (to anyone or nobody) clears [state] at once. */
    fun sessionChanged(active: Session?) {
        synchronized(lock) {
            this.active = active
            generation++
            held.value = null
        }
        firstActive.complete(Unit)
    }

    override suspend fun check(session: Session): AdminStatus {
        // The graph's collector may not have run yet just after start-up: wait for it rather than refuse.
        withTimeoutOrNull(FIRST_SESSION_WAIT_MS) { firstActive.await() } ?: return AdminStatus.NOT_ADMIN
        val asked = synchronized(lock) { if (active != session) return AdminStatus.NOT_ADMIN else generation }
        val answer: AdminStatus? = try {
            val m = me(session)
            if (m.userId == session.userId && m.isAdministrator && !m.isDisabled) AdminStatus.ADMIN else AdminStatus.NOT_ADMIN
        } catch (e: CancellationException) {
            throw e
        } catch (e: AppError) {
            AppLog.d(TAG, "administrator check failed: ${e.kind}")
            if (e.kind in UNREACHABLE) null else AdminStatus.NOT_ADMIN
        } catch (e: Exception) {
            AppLog.d(TAG, "administrator check failed: unexpected")
            AdminStatus.NOT_ADMIN
        }
        synchronized(lock) {
            if (generation != asked || active != session) return AdminStatus.NOT_ADMIN
            if (answer == null) return held.value?.takeIf { it.session == session }?.status ?: AdminStatus.UNKNOWN
            held.value = AdminState(session, answer)
            return answer
        }
    }

    override fun lost(session: Session) {
        synchronized(lock) { if (held.value?.session == session) held.value = AdminState(session, AdminStatus.NOT_ADMIN) }
    }

    override fun allows(session: Session): Boolean = held.value == AdminState(session, AdminStatus.ADMIN)

    private companion object {
        const val TAG = "Admin"
        const val FIRST_SESSION_WAIT_MS = 5_000L
        /** Could not ask: an earlier answer for the same session stands. Anything else is an answer, and only ADMIN opens. */
        val UNREACHABLE = setOf(
            AppError.Kind.OFFLINE, AppError.Kind.NETWORK, AppError.Kind.TIMEOUT, AppError.Kind.SERVER_ERROR,
            AppError.Kind.TLS_UNTRUSTED, AppError.Kind.TLS_PIN_CHANGED,
        )
    }
}
