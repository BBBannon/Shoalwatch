package app.shoalwatch.ui.player

import app.shoalwatch.core.AppLog
import app.shoalwatch.data.downloads.AfterFinish
import app.shoalwatch.data.downloads.Housekeeping
import app.shoalwatch.data.downloads.ReleaseOutcome
import app.shoalwatch.data.playback.PlayMethod
import app.shoalwatch.data.playback.PlaybackState
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * When the player offers watch-and-delete (see "Player screen" in docs/ARCHITECTURE.md). [onState] is fed every playback state:
 * a LOCAL plan whose `finished` has just become true (playback crossed the mark, never a seek) is offered once per (session,
 * item) in this player, if the profile's setting is not NEVER and the download is one it may release. The prompt carries its own
 * session and is dropped when the item, the session or the player changes, or on Keep download; a press acts only as the
 * prompt's session, and a press after it was dropped does nothing. It never touches playback.
 */
internal class WatchedPromptLogic(private val housekeeping: Housekeeping, private val scope: CoroutineScope) {
    private val _prompt = MutableStateFlow<WatchedPromptUi?>(null)
    val prompt: StateFlow<WatchedPromptUi?> = _prompt.asStateFlow()

    private val _messages = Channel<WatchedMessage>(Channel.BUFFERED)
    val messages: Flow<WatchedMessage> = _messages.receiveAsFlow()

    private val offered = mutableSetOf<Pair<Session, String>>()
    private var current: Pair<Session?, String?>? = null
    private var wasFinished = false
    private var deciding: Job? = null

    fun onState(state: PlaybackState) {
        val key = state.session to state.itemId
        if (state.closed || key != current) {
            deciding?.cancel()
            _prompt.value = null
            current = key
            wasFinished = false
        }
        val crossed = state.finished && !wasFinished
        wasFinished = state.finished
        val s = state.session ?: return
        val itemId = state.itemId ?: return
        if (!crossed || state.closed || state.method != PlayMethod.LOCAL || !offered.add(s to itemId)) return
        deciding = scope.launch {
            try {
                val settings = housekeeping.profileSettings(s).first()
                if (settings.afterFinish == AfterFinish.NEVER) return@launch
                val choice = housekeeping.releaseChoice(s, itemId)
                if (!choice.canRelease || current != (s to itemId)) return@launch
                _prompt.value = WatchedPromptUi(s, itemId, choice.film, choice.othersWhoShare, settings.afterFinish == AfterFinish.ALWAYS)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "watched prompt not offered", e) // nothing shows; the download stays
            }
        }
    }

    fun delete() = release(together = false)
    fun together() = release(together = true)
    fun keep() { _prompt.value = null }

    private fun release(together: Boolean) {
        val p = _prompt.value ?: return
        if (p.always) return
        _prompt.value = null // one press only
        scope.launch {
            val message = try {
                when (housekeeping.release(p.session, p.itemId, together)) {
                    ReleaseOutcome.DELETED -> WatchedMessage.DELETED
                    ReleaseOutcome.KEPT_FOR_OTHERS -> WatchedMessage.KEPT_FOR_OTHERS
                    ReleaseOutcome.KEPT_UNTIL_EVERYONE_WATCHED -> WatchedMessage.KEPT_UNTIL_EVERYONE
                    ReleaseOutcome.NOT_YOURS -> WatchedMessage.FAILED
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "watched release failed", e)
                WatchedMessage.FAILED
            }
            _messages.send(message)
        }
    }

    private companion object { const val TAG = "WatchedPrompt" }
}
