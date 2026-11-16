package app.shoalwatch.ui.browse

import app.shoalwatch.core.AppError
import app.shoalwatch.data.library.Freshness
import app.shoalwatch.data.library.Playstate
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class ActionKind { PLAYED, FAVOURITE, REMOVE }

/** What a screen is given when its library flow failed locally: nothing saved, and the failure shown the way any other is. */
val FAILED_LOCALLY = Freshness.Stale(AppError.Kind.UNKNOWN)

/** One tidy-up in progress: which item and which action. The screen shows a small "working" indicator on the control for it. */
data class Work(val itemId: String, val kind: ActionKind)

/** A [Work] and the session that asked for it: item ids belong to a server, so two users of one server can share an id. */
data class OwnedWork(val session: Session, val work: Work)

/**
 * The one place the browsing screens call [Playstate], and the one place a screen's background calls are made safe. Each call
 * runs in [scope] (a ViewModel scope; the action itself is not abandoned if the screen goes), takes the [Session] it was asked
 * for, and:
 *  - puts an [OwnedWork] in [working] until the change is saved on this phone (and queued for the server; nothing waits for the
 *    server, so offline a tick succeeds). The set is per session (see [forSession]): one user's pending action is never shown
 *    to, or allowed to block, another;
 *  - ignores a second tap on the same control, for the same session, while its first is still working;
 *  - on an [AppError] (now only what can still fail: an item this user was not shown, or a local failure, with nothing changed)
 *    sends its `userMessage` to [failures]; anything else becomes a plain "something went wrong" message. A
 *    failure is only shown while its session is still the one on screen ([current]), checked when it is sent and again when it is
 *    about to be shown: it is dropped, not queued, for the next user;
 *  - after a success, runs [onDone] (a favourites-filtered list refreshes itself with it); if that fails the change is not reported
 *    as failed: the message says the list could not be refreshed.
 *
 * [pull] and [attempt] are for the other calls a screen makes (refresh, load more, switching account): a failing local call, such
 * as a full disk, is reported the same way and never ends the app.
 */
class ItemActions(
    private val playstate: Playstate,
    private val scope: CoroutineScope,
    private val connections: ServerConnections,
    private val current: () -> Session?,
) {
    private val _working = MutableStateFlow<Set<OwnedWork>>(emptySet())
    val working: StateFlow<Set<OwnedWork>> = _working

    private val _pulling = MutableStateFlow<Set<Session>>(emptySet())

    /** How many pulls are running per session (main thread only): the indicator stays until the last one has ended. */
    private val pullsRunning = HashMap<Session, Int>()

    /** The sessions whose pull-to-refresh or Retry is running; a screen shows the indicator only if the session on screen is in it. */
    val pulling: StateFlow<Set<Session>> = _pulling

    /** Counts the user's own refreshes ([pull]): a [guard]ed flow that failed waits for the next one, then subscribes again. */
    private val retries = MutableStateFlow(0)

    /** Each message travels with the session it belongs to, so it can be checked again when it is about to be shown. */
    private val failureChannel = Channel<Pair<Session?, String>>(Channel.BUFFERED)

    /**
     * Messages to show in a snackbar, each once; they wait if nothing is collecting yet. A message is shown only while its session
     * is still the one on screen when it is received: one that waited past a switch of account is dropped, not shown to the next user.
     */
    val failures: Flow<String> = failureChannel.receiveAsFlow().filter { (s, _) -> s == current() }.map { it.second }

    fun setPlayed(s: Session, itemId: String, played: Boolean, onDone: suspend () -> Unit = {}) =
        run(s, Work(itemId, ActionKind.PLAYED), onDone) { playstate.setPlayed(s, itemId, played) }

    fun setFavourite(s: Session, itemId: String, favourite: Boolean, onDone: suspend () -> Unit = {}) =
        run(s, Work(itemId, ActionKind.FAVOURITE), onDone) { playstate.setFavourite(s, itemId, favourite) }

    fun removeFromContinueWatching(s: Session, itemId: String, onDone: suspend () -> Unit = {}) =
        run(s, Work(itemId, ActionKind.REMOVE), onDone) { playstate.removeFromContinueWatching(s, itemId) }

    /**
     * The user's own refresh: [pulling] is set for [s] while [block] runs, and a failure is reported. If the server is `Offline`
     * it is re-probed first: nothing is sent while the app believes the server is down, so without this a refresh could never
     * find out that it is back. The probe sets `Checking` at once, so [block]'s request waits for its answer.
     */
    fun pull(s: Session, block: suspend () -> Unit) {
        connections.reprobeIfOffline(s)
        retries.update { it + 1 }
        scope.launch {
            pullsRunning.merge(s, 1, Int::plus)
            _pulling.value = pullsRunning.keys.toSet()
            try {
                attempt(s, block)
            } finally {
                pullsRunning.computeIfPresent(s) { _, n -> if (n <= 1) null else n - 1 }
                _pulling.value = pullsRunning.keys.toSet()
            }
        }
    }

    /** Runs [block] for [s]; a failure becomes a message (while [s] is still on screen) instead of an uncaught exception. True if it did not fail. */
    suspend fun attempt(s: Session?, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        report(s, e)
        false
    }

    /**
     * For a flow the library gives a screen (Room-backed: a damaged database throws while it is collected, which in a ViewModel
     * scope would end the app). A failure is reported like any other and [fallback] is emitted, so the screen shows an error
     * state with Retry rather than a skeleton that never goes. The failed flow then waits for the user's next refresh ([pull],
     * which every Retry and pull-to-refresh goes through) and subscribes to [flow] again, so Retry brings the screen back once
     * the database works again. A flow that has not failed is never restarted. Cancellation and `Error`s pass through
     * untouched. The message is queued for [s] without asking [current], which may not exist yet when a flow fails as the
     * ViewModel starts; it is checked against the session on screen when it is shown, like every message.
     */
    fun <T> guard(s: Session, flow: Flow<T>, fallback: () -> T): Flow<T> = flow {
        while (true) {
            var failedAt: Int? = null
            emitAll(
                flow.catch { e ->
                    if (e !is Exception || e is CancellationException) throw e
                    failedAt = retries.value
                    failureChannel.trySend(s to (e as? AppError ?: AppError.of(AppError.Kind.UNKNOWN)).userMessage)
                    emit(fallback())
                },
            )
            val seen = failedAt ?: return@flow
            retries.first { it != seen }
        }
    }

    companion object {
        /** When a change worked but the list refresh after it did not: the change itself is not reported as failed. */
        const val REFRESH_AFTER_CHANGE_FAILED = "Your change was saved, but the list could not be refreshed."
    }

    private fun report(s: Session?, e: Exception) {
        if (current() != s) return
        failureChannel.trySend(s to (e as? AppError ?: AppError.of(AppError.Kind.UNKNOWN)).userMessage)
    }

    private fun run(s: Session, work: Work, onDone: suspend () -> Unit, call: suspend () -> Unit) {
        val owned = OwnedWork(s, work)
        if (owned in _working.value) return
        _working.update { it + owned }
        scope.launch {
            try {
                // The change and the refresh after it are told apart: a change that worked is never reported as failed.
                if (attempt(s, call)) {
                    try {
                        onDone()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        failureChannel.trySend(s to REFRESH_AFTER_CHANGE_FAILED)
                    }
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
