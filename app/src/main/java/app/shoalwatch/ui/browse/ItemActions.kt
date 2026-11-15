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
import kotlinx.coroutines.launch

enum class ActionKind { PLAYED, FAVOURITE, REMOVE }

/** One tidy-up in progress: which item and which action. The screen shows a small "working" indicator on the control for it. */
data class Work(val itemId: String, val kind: ActionKind)

/**
 * The one place the browsing screens call [Playstate]. Each call runs in [scope] (a ViewModel scope; the action itself is not
 * abandoned if the screen goes), takes the [Session] it was asked for, and:
 *  - puts a [Work] in [working] until the server has answered, because actions for one user run one at a time and a queued tap's
 *    change shows only when the one before it has reached the server: the control shows "working" so a tap never looks missed;
 *  - ignores a second tap on the same control while its first is still working;
 *  - on an [AppError] sends its `userMessage` to [failures] (the change has already been put back by the layer below, and the
 *    screen shows it again because it observes the database); anything else becomes a plain "something went wrong" message;
 *  - after a success, runs [onDone] (a favourites-filtered list refreshes itself with it).
 */
class ItemActions(private val playstate: Playstate, private val scope: CoroutineScope) {
    private val _working = MutableStateFlow<Set<Work>>(emptySet())
    val working: StateFlow<Set<Work>> = _working

    private val failureChannel = Channel<String>(Channel.BUFFERED)

    /** Messages to show in a snackbar, each once; they wait if nothing is collecting yet. */
    val failures: Flow<String> = failureChannel.receiveAsFlow()

    fun setPlayed(s: Session, itemId: String, played: Boolean, onDone: suspend () -> Unit = {}) =
        run(Work(itemId, ActionKind.PLAYED), onDone) { playstate.setPlayed(s, itemId, played) }

    fun setFavourite(s: Session, itemId: String, favourite: Boolean, onDone: suspend () -> Unit = {}) =
        run(Work(itemId, ActionKind.FAVOURITE), onDone) { playstate.setFavourite(s, itemId, favourite) }

    fun removeFromContinueWatching(s: Session, itemId: String, onDone: suspend () -> Unit = {}) =
        run(Work(itemId, ActionKind.REMOVE), onDone) { playstate.removeFromContinueWatching(s, itemId) }

    private fun run(work: Work, onDone: suspend () -> Unit, call: suspend () -> Unit) {
        if (work in _working.value) return
        _working.value = _working.value + work
        scope.launch {
            try {
                call()
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: AppError) {
                failureChannel.trySend(e.userMessage)
            } catch (e: Exception) {
                failureChannel.trySend(AppError.of(AppError.Kind.UNKNOWN).userMessage)
            } finally {
                _working.value = _working.value - work
            }
        }
    }
}

/** True when any action for [itemId] is in progress. */
fun Set<Work>.isWorking(itemId: String): Boolean = any { it.itemId == itemId }
