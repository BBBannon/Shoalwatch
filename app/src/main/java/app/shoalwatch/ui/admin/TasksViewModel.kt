package app.shoalwatch.ui.admin

import androidx.lifecycle.viewModelScope
import app.shoalwatch.R
import app.shoalwatch.data.admin.Admin
import app.shoalwatch.data.admin.AdminRights
import app.shoalwatch.data.admin.ServerTask
import app.shoalwatch.data.admin.TaskState
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerConnections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.Instant

/** While a task runs or is stopping, the tasks screen reloads this often; otherwise every [TASKS_IDLE_POLL_MS]. */
const val TASKS_BUSY_POLL_MS = 3_000L
const val TASKS_IDLE_POLL_MS = 30_000L

/** The tasks of one category, in the server's order. An empty [category] is shown as "Other". */
data class TaskGroup(val category: String, val tasks: List<ServerTask>) {
    override fun toString() = "TaskGroup(<redacted>)"
}

data class StopTaskConfirm(val taskId: String, val name: String) {
    override fun toString() = "StopTaskConfirm(<redacted>)"
}

data class TasksUiState(val frame: AdminFrame = AdminFrame(), val groups: List<TaskGroup> = emptyList(), val confirm: StopTaskConfirm? = null) {
    override fun toString() = "TasksUiState(<redacted>)"
}

/**
 * Scheduled tasks by category: state, progress and last run; "Run now" for an idle task (no confirmation: running a task again
 * is what it is for) and Stop for a running one after confirmation (it abandons work part way).
 */
class TasksViewModel(
    catalog: ServerCatalog,
    rights: AdminRights,
    connections: ServerConnections,
    private val admin: Admin,
    clock: () -> Instant = Instant::now,
) : AdminScreenModel(catalog, rights, connections, clock) {
    private val tasks = MutableStateFlow<List<ServerTask>>(emptyList())
    private val confirmState = MutableStateFlow<StopTaskConfirm?>(null)

    private val poll = poller { session ->
        val list = admin.tasks(session)
        if (guard.gate.value is AdminGate.Open) tasks.value = list
        if (list.any { it.state == TaskState.RUNNING || it.state == TaskState.CANCELLING }) TASKS_BUSY_POLL_MS else TASKS_IDLE_POLL_MS
    }

    val state: StateFlow<TasksUiState> = combine(frames, tasks, confirmState) { frame, list, confirm ->
        if (frame.gate == AdminGate.Closed) TasksUiState(frame = frame) else TasksUiState(frame, group(list), confirm)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TasksUiState())

    fun refresh() = poll.kick()

    fun run(taskId: String) {
        val task = tasks.value.firstOrNull { it.id == taskId && it.state == TaskState.IDLE } ?: return
        work.run("task:$taskId", { s -> admin.startTask(s, taskId) }) {
            notify(AdminNotice(R.string.admin_task_started, listOf(task.name), R.string.admin_untitled))
            poll.kick()
        }
    }

    fun askStop(taskId: String) {
        val task = running(taskId) ?: return
        confirmState.value = StopTaskConfirm(task.id, task.name)
    }

    /** Stops the confirmed task once, if it is still running in the latest list (else GONE and a refresh); the dialog closes. */
    fun confirmStop() {
        val c = confirmState.value ?: return
        confirmState.value = null
        if (frames.value.gate !is AdminGate.Open) return
        if (running(c.taskId) == null) {
            work.fail(AdminProblem.GONE)
            poll.kick()
            return
        }
        work.run("task:${c.taskId}", { s -> admin.stopTask(s, c.taskId) }) { poll.kick() }
    }

    fun dismissConfirm() {
        confirmState.value = null
    }

    override fun dropLoaded() {
        tasks.value = emptyList()
        confirmState.value = null
    }

    private fun running(id: String) = tasks.value.firstOrNull { it.id == id && it.state == TaskState.RUNNING }

    private companion object {
        /** Named categories in alphabetical order (any case), then the tasks with no category ("Other"). */
        fun group(list: List<ServerTask>): List<TaskGroup> = list.groupBy { it.category }
            .map { (category, tasks) -> TaskGroup(category, tasks) }
            .sortedWith(compareBy<TaskGroup> { it.category.isEmpty() }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.category })
    }
}
