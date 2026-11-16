package app.shoalwatch.ui.admin

import androidx.annotation.StringRes
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A one-line result shown on an admin screen ("Message sent to kid."). [args] are display text already made safe; an empty one
 * is shown as the neutral word [fallback] ("Unnamed user") when there is one.
 */
data class AdminNotice(@StringRes val text: Int, val args: List<String> = emptyList(), @StringRes val fallback: Int? = null) {
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
     * OFFLINE, busy is ignored). Never retried. Cancelled if the gate closes while it runs, so an action still waiting to be
     * written is never sent. On success [onDone] runs; on failure the problem is set (or nothing, if the gate closed).
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
            // The gate closing (a switch, a sign-out, lost rights) cancels an action still waiting, for the address say: it is
            // then never sent. One already written completes on the server; its answer is dropped below.
            val work = this
            val closeWatch = launch { guard.gate.first { it == AdminGate.Closed }; work.cancel() }
            try {
                action(gate.session)
                closeWatch.cancel()
                // An answer that arrives after the gate closed is dropped: nothing more is shown for it.
                if (guard.gate.value is AdminGate.Open) onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                closeWatch.cancel()
                problemState.value = guard.problemFor(e)
            } finally {
                closeWatch.cancel()
                runningKey.value = null
            }
        }
    }

    /**
     * Shows [problem] without sending anything (a confirmed target that is no longer in the latest list is GONE). Ignored unless
     * the gate is Open.
     */
    fun fail(problem: AdminProblem) {
        if (guard.gate.value is AdminGate.Open) problemState.value = problem
    }

    fun dismissProblem() {
        problemState.value = null
    }
}
