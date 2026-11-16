package app.shoalwatch.ui.admin

import androidx.lifecycle.viewModelScope
import app.shoalwatch.R
import app.shoalwatch.core.AppError
import app.shoalwatch.data.admin.Admin
import app.shoalwatch.data.admin.AdminRights
import app.shoalwatch.data.admin.ServerUser
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant

data class UserRow(
    val user: ServerUser,
    /** Library names in the server's order; null when they could not be read. */
    val libraryNames: List<String>?,
) {
    override fun toString() = "UserRow(<redacted>)"
}

data class UserConfirm(val userId: String, val name: String, val disable: Boolean, val isAdministrator: Boolean) {
    override fun toString() = "UserConfirm(<redacted>)"
}

data class UsersUiState(val frame: AdminFrame = AdminFrame(), val users: List<UserRow> = emptyList(), val confirm: UserConfirm? = null) {
    override fun toString() = "UsersUiState(<redacted>)"
}

/** What the "Libraries:" line of a user says. */
sealed interface LibraryLine {
    data object All : LibraryLine
    data object None : LibraryLine
    /** The names could not be read: "Libraries: 2 chosen". */
    data class Count(val chosen: Int) : LibraryLine
    /** Up to [SHOWN_LIBRARIES] names, and how many more there are (beyond those, or ids without a name). */
    data class Names(val names: List<String>, val more: Int) : LibraryLine {
        override fun toString() = "Names(<redacted>)"
    }
}

/** At most this many library names are written on a user's card. */
const val SHOWN_LIBRARIES = 3

fun libraryLine(row: UserRow): LibraryLine {
    val user = row.user
    val names = row.libraryNames
    return when {
        user.allLibraries -> LibraryLine.All
        user.libraryIds.isEmpty() -> LibraryLine.None
        names == null -> LibraryLine.Count(user.libraryIds.size)
        else -> {
            val shown = names.take(SHOWN_LIBRARIES)
            LibraryLine.Names(shown, user.libraryIds.size - shown.size)
        }
    }
}

/**
 * Users: each account with its key settings, and enable or disable after confirmation. The signed-in account itself and an
 * account whose settings could not be read are never offered. Loads on open and on Refresh only.
 */
class UsersViewModel(
    catalog: ServerCatalog,
    rights: AdminRights,
    connections: ServerConnections,
    private val admin: Admin,
    clock: () -> Instant = Instant::now,
) : AdminScreenModel(catalog, rights, connections, clock) {
    private val users = MutableStateFlow<List<UserRow>>(emptyList())
    private val confirmState = MutableStateFlow<UserConfirm?>(null)

    private val poll = poller { session ->
        val list = admin.users(session)
        val names = libraryNames(session)
        val rows = list.map { u -> UserRow(u, names?.let { n -> u.libraryIds.mapNotNull { id -> n[id]?.ifEmpty { null } } }) }
        if (guard.gate.value is AdminGate.Open) users.value = rows
        null
    }

    val state: StateFlow<UsersUiState> = combine(frames, users, confirmState) { frame, rows, confirm ->
        if (frame.gate == AdminGate.Closed) UsersUiState(frame = frame) else UsersUiState(frame, rows, confirm)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UsersUiState())

    fun refresh() = poll.kick()

    fun askDisable(userId: String) = ask(userId, disable = true)

    fun askEnable(userId: String) = ask(userId, disable = false)

    /** Sends the confirmed change once, if the user is still in the latest list in the opposite state (else GONE and a refresh). */
    fun confirm() {
        val c = confirmState.value ?: return
        confirmState.value = null
        if (frames.value.gate !is AdminGate.Open) return
        if (changeable(c.userId, c.disable) == null) {
            work.fail(AdminProblem.GONE)
            poll.kick()
            return
        }
        work.run("user:${c.userId}", { s -> admin.setUserDisabled(s, c.userId, c.disable) }) {
            val done = if (c.disable) R.string.admin_user_disabled_done else R.string.admin_user_enabled_done
            notify(AdminNotice(done, listOf(c.name), R.string.admin_unnamed_user))
            poll.kick()
        }
    }

    fun dismissConfirm() {
        confirmState.value = null
    }

    override fun dropLoaded() {
        users.value = emptyList()
        confirmState.value = null
    }

    private fun ask(userId: String, disable: Boolean) {
        val u = changeable(userId, disable) ?: return
        confirmState.value = UserConfirm(u.id, u.name, disable, u.isAdministrator)
    }

    /** A user in the latest list that this screen may change to [disable]: not you, settings read, and not already so. */
    private fun changeable(id: String, disable: Boolean) =
        users.value.map { it.user }.firstOrNull { it.id == id && !it.isYou && it.policyKnown && it.isDisabled != disable }

    /** Library id to name, or null: a failure here is not a load problem, unless it ends the sign-in. */
    private suspend fun libraryNames(session: Session): Map<String, String>? = try {
        admin.libraries(session).associate { it.id to it.name }
    } catch (e: CancellationException) {
        throw e
    } catch (e: AppError) {
        if (e.kind == AppError.Kind.UNAUTHORISED || e.kind == AppError.Kind.NOT_SIGNED_IN) throw e
        null
    }
}
