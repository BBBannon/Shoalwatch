package app.shoalwatch.ui.admin

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.shoalwatch.R
import app.shoalwatch.data.admin.ServerTask
import app.shoalwatch.data.admin.TaskOutcome
import app.shoalwatch.data.admin.TaskState
import java.time.Instant

class TasksActions(
    val onBack: () -> Unit,
    val onRefresh: () -> Unit,
    val onRun: (taskId: String) -> Unit,
    val onStop: (taskId: String) -> Unit,
    val onConfirmStop: () -> Unit,
    val onDismissConfirm: () -> Unit,
    val onDismissProblem: () -> Unit = {},
    val onDismissNotice: () -> Unit = {},
)

/** Scheduled tasks under a heading per category: what each does, its state, its last run, and Run now or Stop. */
@Composable
fun TasksScreen(state: TasksUiState, actions: TasksActions) {
    val text = rememberAdminText()
    val now = state.frame.updatedAt ?: Instant.now()
    AdminScaffold(
        stringResource(R.string.admin_tasks_title), state.frame, actions.onBack, actions.onRefresh, actions.onDismissProblem, actions.onDismissNotice,
    ) {
        if (state.groups.isEmpty() && state.frame.updatedAt != null) {
            item(key = "empty") { Text(stringResource(R.string.admin_tasks_empty), style = MaterialTheme.typography.bodyMedium) }
        }
        state.groups.forEach { group ->
            item(key = "category-${group.category}") {
                Text(
                    group.category.ifEmpty { stringResource(R.string.admin_task_category_other) },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                )
            }
            items(group.tasks, key = { "task-${it.id}" }) { task ->
                TaskCard(task, text, now, state.frame.canAct, { actions.onRun(task.id) }, { actions.onStop(task.id) })
            }
        }
    }
    if (state.frame.gate is AdminGate.Open) {
        state.confirm?.let { c ->
            val name = text.name(c.name, R.string.admin_untitled)
            AdminConfirmDialog(
                stringResource(R.string.admin_confirm_task_stop_title, name),
                listOf(stringResource(R.string.admin_confirm_task_stop_body)),
                stringResource(R.string.admin_confirm_task_stop_action),
                state.frame.canAct, actions.onConfirmStop, actions.onDismissConfirm,
            )
        }
    }
}

@Composable
private fun TaskCard(task: ServerTask, text: AdminText, now: Instant, canAct: Boolean, onRun: () -> Unit, onStop: () -> Unit) {
    val name = text.name(task.name, R.string.admin_untitled)
    AdminCard {
        Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        if (task.description.isNotEmpty()) {
            Text(task.description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (task.state) {
            TaskState.IDLE -> Text(stringResource(R.string.admin_task_idle), style = MaterialTheme.typography.bodyMedium)
            TaskState.RUNNING -> AdminProgress(
                task.progressPercent,
                task.progressPercent?.let { stringResource(R.string.admin_task_running, it.toString()) } ?: stringResource(R.string.admin_task_running_unknown),
            )
            TaskState.CANCELLING -> AdminProgress(null, stringResource(R.string.admin_task_cancelling))
        }
        val last = task.lastRun
        Text(
            if (last == null) stringResource(R.string.admin_task_never)
            else stringResource(R.string.admin_task_last, text.ago(last.endedAt, now), text.outcome(last.outcome)),
            style = MaterialTheme.typography.bodyMedium,
        )
        val error = last?.errorMessage?.takeIf { it.isNotEmpty() && (last.outcome == TaskOutcome.FAILED || last.outcome == TaskOutcome.ABORTED) }
        if (error != null) Text(stringResource(R.string.admin_task_error, error), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        when (task.state) {
            TaskState.IDLE -> AdminAction(stringResource(R.string.admin_task_run), stringResource(R.string.admin_task_run_cd, name), canAct, onClick = onRun)
            TaskState.RUNNING -> AdminAction(stringResource(R.string.admin_task_stop), stringResource(R.string.admin_task_stop_cd, name), canAct, caution = true, onClick = onStop)
            TaskState.CANCELLING -> Unit
        }
    }
}
