package app.shoalwatch.ui.admin

import android.content.Context
import app.shoalwatch.data.admin.ActivityPage
import app.shoalwatch.data.admin.Admin
import app.shoalwatch.data.admin.AdminRights
import app.shoalwatch.data.admin.AdminState
import app.shoalwatch.data.admin.AdminStatus
import app.shoalwatch.data.admin.DashboardOpener
import app.shoalwatch.data.admin.DashboardResult
import app.shoalwatch.data.admin.LibraryFolder
import app.shoalwatch.data.admin.RemoteCommand
import app.shoalwatch.data.admin.ServerDevice
import app.shoalwatch.data.admin.ServerStatus
import app.shoalwatch.data.admin.ServerTask
import app.shoalwatch.data.admin.ServerUser
import app.shoalwatch.data.admin.WatchSession
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.MainDispatcherRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * [AdminRights] for ViewModel tests: [check] answers [checkAnswer] (ADMIN by default) and records it as the real one does
 * (UNKNOWN leaves [state] alone); [held] can be set directly to model a switch (null) or a demotion.
 */
class FakeAdminRights(var answer: AdminStatus = AdminStatus.ADMIN) : AdminRights {
    val held = MutableStateFlow<AdminState?>(null)
    override val state: StateFlow<AdminState?> = held
    var checks = 0
    val lostCalls = mutableListOf<Session>()
    var checkAnswer: suspend (Session) -> AdminStatus = { answer }

    override suspend fun check(session: Session): AdminStatus {
        checks++
        val status = checkAnswer(session)
        if (status != AdminStatus.UNKNOWN) held.value = AdminState(session, status)
        return status
    }

    override fun lost(session: Session) {
        lostCalls += session
        if (held.value?.session == session) held.value = AdminState(session, AdminStatus.NOT_ADMIN)
    }

    override fun allows(session: Session): Boolean = held.value == AdminState(session, AdminStatus.ADMIN)
}

/**
 * [Admin] for ViewModel tests: every read answers its `var` (or throws [failWith] / its own failure), every call is recorded in
 * [calls] as "name" or "name:arg", and [gate] (when set) suspends a call until completed, to see what happens meanwhile.
 */
class FakeAdmin : Admin {
    var status = ServerStatus("Test Jellyfin", "12.4.0", pendingRestart = false, shuttingDown = false)
    var sessions: List<WatchSession> = emptyList()
    var libraries: List<LibraryFolder> = emptyList()
    var tasks: List<ServerTask> = emptyList()
    var users: List<ServerUser> = emptyList()
    var activity: (Int, Int) -> ActivityPage = { start, _ -> ActivityPage(emptyList(), start, true) }
    var devices: List<ServerDevice> = emptyList()

    /** Thrown by every call while set. */
    var failWith: Throwable? = null
    /** Thrown by the call of this name only. */
    val failures = mutableMapOf<String, Throwable>()
    /** Suspends every call until it completes, while set. */
    var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null

    val calls = mutableListOf<String>()
    val sessionsSeen = mutableListOf<Session>()
    /** Every message sent, as (sessionId, header, text). */
    val messages = mutableListOf<Triple<String, String, String>>()

    private suspend fun <T> call(name: String, s: Session, answer: () -> T): T {
        calls += name
        sessionsSeen += s
        gate?.await()
        (failures[name.substringBefore(':')] ?: failWith)?.let { throw it }
        return answer()
    }

    override suspend fun serverStatus(s: Session) = call("serverStatus", s) { status }
    override suspend fun sessions(s: Session) = call("sessions", s) { sessions }
    override suspend fun sendMessage(s: Session, sessionId: String, header: String, text: String) =
        call("sendMessage:$sessionId", s) { messages += Triple(sessionId, header, text) }
    override suspend fun command(s: Session, sessionId: String, command: RemoteCommand) = call("command:$sessionId:${command.wire}", s) {}
    override suspend fun libraries(s: Session) = call("libraries", s) { libraries }
    override suspend fun scanAll(s: Session) = call("scanAll", s) {}
    override suspend fun scanLibrary(s: Session, libraryId: String) = call("scanLibrary:$libraryId", s) {}
    override suspend fun tasks(s: Session) = call("tasks", s) { tasks }
    override suspend fun startTask(s: Session, taskId: String) = call("startTask:$taskId", s) {}
    override suspend fun stopTask(s: Session, taskId: String) = call("stopTask:$taskId", s) {}
    override suspend fun users(s: Session) = call("users", s) { users }
    override suspend fun setUserDisabled(s: Session, userId: String, disabled: Boolean) = call("setUserDisabled:$userId:$disabled", s) {}
    override suspend fun activity(s: Session, startIndex: Int, limit: Int) = call("activity:$startIndex", s) { activity(startIndex, limit) }
    override suspend fun devices(s: Session) = call("devices", s) { devices }
    override suspend fun removeDevice(s: Session, deviceId: String) = call("removeDevice:$deviceId", s) {}
    override suspend fun restart(s: Session) = call("restart", s) {}
    override suspend fun shutDown(s: Session) = call("shutDown", s) {}
}

/** [DashboardOpener] that answers [result] and records the sessions (and contexts) it was asked for. */
class FakeDashboardOpener(var result: DashboardResult = DashboardResult.OPENED) : DashboardOpener {
    val opened = mutableListOf<Session>()
    val contexts = mutableListOf<Context>()
    override suspend fun open(session: Session, context: Context): DashboardResult {
        opened += session
        contexts += context
        return result
    }
}

/** The admin ViewModels a test made, so they can all be hidden when it ends (see [adminTest]). */
class ModelsInTest {
    private val made = mutableListOf<AdminScreenModel>()
    fun <T : AdminScreenModel> add(model: T): T = model.also { made += it }
    fun hideAll() = made.forEach { it.setVisible(false) }
}

/**
 * runTest on the main test dispatcher that hides every model in [models] when [block] ends, pass or fail: a visible admin
 * screen polls for ever in virtual time, so a failed assertion would otherwise leave runTest's clean-up spinning.
 */
fun MainDispatcherRule.adminTest(models: ModelsInTest, block: suspend TestScope.() -> Unit) = runTest(dispatcher) {
    try {
        block()
    } finally {
        models.hideAll()
    }
}
