package app.shoalwatch.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.data.admin.AdminRights
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/** What every admin screen draws around its own content. */
data class AdminFrame(
    val gate: AdminGate = AdminGate.Checking,
    val loading: Boolean = true,
    val updatedAt: Instant? = null,
    val loadProblem: AdminProblem? = null,
    val running: String? = null,
    val problem: AdminProblem? = null,
    val notice: AdminNotice? = null,
) {
    val online: Boolean get() = (gate as? AdminGate.Open)?.online == true

    /** Buttons that act on the server are enabled only when this is true. */
    val canAct: Boolean get() = online && running == null

    override fun toString() = "AdminFrame(<redacted>)"
}

/**
 * The base of every admin ViewModel: an [AdminGuard] (the session rule), an [AdminWork] (one action at a time) and [poller]s
 * (load only while visible, open and online). When the gate closes, [clear] drops everything the screen loaded and any open
 * confirmation or typed text; nothing from an admin screen outlives the session that opened it. Nothing is saved anywhere.
 */
abstract class AdminScreenModel(
    catalog: ServerCatalog,
    rights: AdminRights,
    connections: ServerConnections,
    protected val clock: () -> Instant,
) : ViewModel() {
    protected val guard: AdminGuard = AdminGuard(catalog, rights, connections, viewModelScope)
    protected val work: AdminWork = AdminWork(guard, viewModelScope)

    private data class LoadState(val loading: Boolean = true, val updatedAt: Instant? = null, val problem: AdminProblem? = null)

    private val loadState = MutableStateFlow(LoadState())
    private val noticeState = MutableStateFlow<AdminNotice?>(null)

    private val frameState: StateFlow<AdminFrame> =
        combine(guard.gate, loadState, work.running, work.problem, noticeState) { gate, load, running, problem, notice ->
            if (gate == AdminGate.Closed) AdminFrame(gate = AdminGate.Closed, loading = false)
            else AdminFrame(gate, load.loading, load.updatedAt, load.problem, running, problem, notice)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, AdminFrame())

    /**
     * Started on first use of [frame] or [setVisible], so [clear] never runs before the subclass is constructed. Until then
     * nothing can have been loaded: a poller loads only once the screen says it is visible.
     */
    private val watchingClose = AtomicBoolean(false)

    val frame: StateFlow<AdminFrame>
        get() {
            watchClose()
            return frameState
        }

    fun setVisible(on: Boolean) {
        watchClose()
        guard.setVisible(on)
    }

    fun dismissProblem() = work.dismissProblem()

    fun dismissNotice() {
        noticeState.value = null
    }

    protected fun notify(notice: AdminNotice) {
        noticeState.value = notice
    }

    /** A poller whose [fetch] failures become `frame.loadProblem` (or nothing, if they closed the gate); a success clears it and stamps `updatedAt`. */
    protected fun poller(fetch: suspend (Session) -> Long?): AdminPoller = AdminPoller(viewModelScope, guard.live) { session ->
        try {
            val next = fetch(session)
            loadState.value = LoadState(loading = false, updatedAt = clock(), problem = null)
            next
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val problem = guard.problemFor(e)
            loadState.update { it.copy(loading = false, problem = problem) }
            if (problem == null) null else RETRY_AFTER_FAILURE_MS
        }
    }

    /** Called (on the main thread) the moment the gate closes: drop every loaded list, confirmation and typed text. */
    protected abstract fun clear()

    private fun watchClose() {
        if (!watchingClose.compareAndSet(false, true)) return
        viewModelScope.launch {
            guard.gate.first { it == AdminGate.Closed }
            loadState.value = LoadState(loading = false)
            noticeState.value = null
            work.dismissProblem()
            clear()
        }
    }

    private companion object {
        /** After a failed load the poller tries again on its own after this long (or at once on Refresh). */
        const val RETRY_AFTER_FAILURE_MS = 15_000L
    }
}
