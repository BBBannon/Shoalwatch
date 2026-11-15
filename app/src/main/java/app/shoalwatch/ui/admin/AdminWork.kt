package app.shoalwatch.ui.admin

import androidx.annotation.StringRes
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A one-line result shown on an admin screen ("Message sent to kid."). [args] are display text already made safe. */
data class AdminNotice(@StringRes val text: Int, val args: List<String> = emptyList()) {
    override fun toString() = "AdminNotice(<redacted>)"
}

/** One server action at a time for one admin screen, as the gate's session, sent once and never retried. */
class AdminWork(private val guard: AdminGuard, private val scope: CoroutineScope) {
    private val runningKey = MutableStateFlow<String?>(null)
    /** The key of the action running now, or null. */
    val running: StateFlow<String?> = runningKey.asStateFlow()

    private val problemState = MutableStateFlow<AdminProblem?>(null)
    val problem: StateFlow<AdminProblem?> = problemState.asStateFlow()

    /**
     * Runs [action] once, as the gate's session, only if the gate is Open and online and nothing is running (else: offline sets
     * OFFLINE, busy is ignored). Never retried. On success [onDone] runs; on failure the problem is set (or nothing, if the gate
     * closed because of it).
     */
    fun run(key: String, action: suspend (Session) -> Unit, onDone: () -> Unit = {}) {
        val gate = guard.gate.value as? AdminGate.Open ?: return
        if (!gate.online) {
            problemState.value = AdminProblem.OFFLINE
            return
        }
        if (runningKey.value != null) return
        runningKey.value = key
        problemState.value = null
        scope.launch {
            try {
                action(gate.session)
                // An answer that arrives after the gate closed is dropped: nothing more is shown for it.
                if (guard.gate.value is AdminGate.Open) onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                problemState.value = guard.problemFor(e)
            } finally {
                runningKey.value = null
            }
        }
    }

    fun dismissProblem() {
        problemState.value = null
    }
}
