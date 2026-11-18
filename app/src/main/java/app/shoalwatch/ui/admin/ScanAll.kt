package app.shoalwatch.ui.admin

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.ui.res.stringResource
import app.shoalwatch.R
import app.shoalwatch.core.AppError
import app.shoalwatch.data.admin.Admin
import app.shoalwatch.data.admin.FULL_SCAN_TASK_KEY
import app.shoalwatch.data.admin.ServerTask
import app.shoalwatch.data.admin.TaskState
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.components.PrimaryButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "Scan all libraries", the one rule for the Server admin dashboard and the Libraries screen (1.0.1: it is what admins do most,
 * so it is at the top of both). Its state is the server's "Scan Media Library" task ([fullScan]), read by each screen's own
 * poller through [read]; it starts without a confirmation (a scan changes nothing that cannot simply be repeated), through the
 * screen's [AdminWork], so it runs once, as the gate's session, and never while another action runs.
 */
internal class ScanAll(private val admin: Admin) {
    private val task = MutableStateFlow<ServerTask?>(null)

    /** The full scan's task: its state and progress are the full scan's. Null when the server has none, or nothing is loaded. */
    val fullScan: StateFlow<ServerTask?> = task.asStateFlow()

    /**
     * The full scan's task, or null: the tasks part failing is not a load problem (the screen works without it), unless the
     * failure ends the sign-in, which must still close the gate.
     */
    suspend fun read(session: Session): ServerTask? = try {
        admin.tasks(session).firstOrNull { it.key == FULL_SCAN_TASK_KEY }
    } catch (e: CancellationException) {
        throw e
    } catch (e: AppError) {
        if (e.kind == AppError.Kind.UNAUTHORISED || e.kind == AppError.Kind.NOT_SIGNED_IN) throw e
        null
    }

    /** What [read] answered, kept only while the gate is open (the caller checks). */
    fun keep(scan: ServerTask?) {
        task.value = scan
    }

    fun drop() {
        task.value = null
    }

    /** Starts the full scan unless [canScanAll] says no; [onStarted] once the server has accepted it. */
    fun start(frame: AdminFrame, work: AdminWork, onStarted: () -> Unit) {
        if (!canScanAll(frame, task.value)) return
        work.run(KEY, { s -> admin.scanAll(s) }, onStarted)
    }

    companion object {
        const val KEY = "scan:all"

        /** What both screens say once the server has started it. */
        val STARTED = AdminNotice(R.string.admin_scan_started_all)
    }
}

/** "Scan all libraries" is on unless the full scan is already running, and only when the screen can act. */
fun canScanAll(frame: AdminFrame, fullScan: ServerTask?): Boolean = frame.canAct && fullScan?.state != TaskState.RUNNING

/** The button, then the full scan's progress while it runs (announced politely, as every [AdminProgress]). */
internal fun LazyListScope.scanAllItems(fullScan: ServerTask?, enabled: Boolean, onScanAll: () -> Unit) {
    item(key = "scan-all") { PrimaryButton(stringResource(R.string.admin_scan_all), onScanAll, enabled = enabled) }
    fullScan?.takeIf { it.state == TaskState.RUNNING }?.let { scan ->
        item(key = "scan-all-progress") {
            val label = scan.progressPercent?.let { stringResource(R.string.admin_scan_all_running, it.toString()) }
                ?: stringResource(R.string.admin_scan_all_running_unknown)
            AdminProgress(scan.progressPercent, label)
        }
    }
}
