package app.shoalwatch.ui.browse

import app.shoalwatch.core.AppError
import app.shoalwatch.data.library.Playstate
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ActionKind { PLAYED, FAVOURITE, REMOVE }

/** One tidy-up in progress: which item and which action. The screen shows a small "working" indicator on the control for it. */
data class Work(val itemId: String, val kind: ActionKind)

/** A [Work] and the session that asked for it: item ids belong to a server, so two users of one server can share an id. */
data class OwnedWork(val session: Session, val work: Work)

/**
 * The one place the browsing screens call [Playstate], and the one place a screen's background calls are made safe. Each call
 * runs in [scope] (a ViewModel scope; the action itself is not abandoned if the screen goes), takes the [Session] it was asked
 * for, and:
 *  - puts an [OwnedWork] in [working] until the server has answered, because actions for one user run one at a time and a queued
 *    tap's change shows only when the one before it has reached the server: the control shows "working" so a tap never looks
 *    missed. The set is per session (see [forSession]): one user's pending action is never shown to, or allowed to block, another;
 *  - ignores a second tap on the same control, for the same session, while its first is still working;
 *  - on an [AppError] sends its `userMessage` to [failures] (the change has already been put back by the layer below, and the
 *    screen shows it again because it observes the database); anything else becomes a plain "something went wrong" message. A
 *    failure is only shown while its session is still the one on screen ([current]): it is dropped, not queued, for the next user;
 *  - after a success, runs [onDone] (a favourites-filtered list refreshes itself with it).
 *
 * [pull] and [attempt] are for the other calls a screen makes (refresh, load more, switching account): a failing local call, such
 * as a full disk, is reported the same way and never ends the app.
 */
class ItemActions(private val playstate: Playstate, private val scope: CoroutineScope, private val current: () -> Session?) {
    private val _working = MutableStateFlow<Set<OwnedWork>>(emptySet())
    val working: StateFlow<Set<OwnedWork>> = _working

    private val _pulling = MutableStateFlow<Session?>(null)

    /** The session whose pull-to-refresh or Retry is running, if any; compare it with the session on screen. */
    val pulling: StateFlow<Session?> = _pulling

    private val failureChannel = Channel<String>(Channel.BUFFERED)

    /** Messages to show in a snackbar, each once; they wait if nothing is collecting yet. */
    val failures: Flow<String> = failureChannel.receiveAsFlow()

    fun setPlayed(s: Session, itemId: String, played: Boolean, onDone: suspend () -> Unit = {}) =
        run(s, Work(itemId, ActionKind.PLAYED), onDone) { playstate.setPlayed(s, itemId, played) }

    fun setFavourite(s: Session, itemId: String, favourite: Boolean, onDone: suspend () -> Unit = {}) =
        run(s, Work(itemId, ActionKind.FAVOURITE), onDone) { playstate.setFavourite(s, itemId, favourite) }

    fun removeFromContinueWatching(s: Session, itemId: String, onDone: suspend () -> Unit = {}) =
        run(s, Work(itemId, ActionKind.REMOVE), onDone) { playstate.removeFromContinueWatching(s, itemId) }

    /** The user's own refresh: [pulling] is set for [s] while [block] runs, and a failure is reported. */
    fun pull(s: Session, block: suspend () -> Unit) {
        scope.launch {
            _pulling.value = s
            try {
                attempt(s, block)
            } finally {
                _pulling.compareAndSet(s, null)
            }
        }
    }

    /** Runs [block] for [s]; a failure becomes a message (while [s] is still on screen) instead of an uncaught exception. */
    suspend fun attempt(s: Session?, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(s, e)
        }
    }

    private fun report(s: Session?, e: Exception) {
        if (current() != s) return
        failureChannel.trySend((e as? AppError ?: AppError.of(AppError.Kind.UNKNOWN)).userMessage)
    }

    private fun run(s: Session, work: Work, onDone: suspend () -> Unit, call: suspend () -> Unit) {
        val owned = OwnedWork(s, work)
        if (owned in _working.value) return
        _working.update { it + owned }
        scope.launch {
            try {
                attempt(s) {
                    call()
                    onDone()
                }
            } finally {
                _working.update { it - owned }
            }
        }
    }
}

/** What [s] has in progress, as the screens show it; nothing for a session that has none (or for nobody). */
fun Set<OwnedWork>.forSession(s: Session?): Set<Work> = if (s == null) emptySet() else filter { it.session == s }.mapTo(HashSet()) { it.work }

/** True when any action for [itemId] is in progress. */
fun Set<Work>.isWorking(itemId: String): Boolean = any { it.itemId == itemId }
