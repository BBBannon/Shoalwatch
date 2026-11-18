package app.shoalwatch.ui.player

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import app.shoalwatch.core.AppLog
import app.shoalwatch.data.downloads.AfterFinish
import app.shoalwatch.data.downloads.Housekeeping
import app.shoalwatch.data.downloads.ReleaseOutcome
import app.shoalwatch.data.playback.PlayMethod
import app.shoalwatch.data.playback.PlaybackState
import app.shoalwatch.data.servers.AccountSummary
import app.shoalwatch.data.servers.Session
import app.shoalwatch.security.PinCheck
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
        // A PIN asked for one profile's press is never answered for another (the next item of the same profile goes on).
        if (asking != null && state.session != null && state.session != asking?.prompt?.session) {
            asking = null
            pin.clearText()
            _sharerPin.value = null
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

    /** "Watched together" asking each protected sharer's own PIN in turn (security review M8 F12); never holds the PIN. */
    private val _sharerPin = MutableStateFlow<SharerPinAsk?>(null)
    val sharerPin: StateFlow<SharerPinAsk?> = _sharerPin.asStateFlow()

    /** The PIN typed for [sharerPin]; cleared after every try. */
    val pin = TextFieldState()
    private var asking: Asking? = null

    private class Asking(val prompt: WatchedPromptUi, val queue: ArrayDeque<AccountSummary>, var leftOut: Boolean = false)

    fun delete() = release(together = false)
    fun keep() { _prompt.value = null }

    /** A protected sharer is asked for its own PIN first; one whose PIN is not given is left out, the others are still marked. */
    fun together() {
        val p = _prompt.value ?: return
        if (p.always) return
        _prompt.value = null // one press only
        scope.launch {
            val locked = try {
                housekeeping.protectedSharers(p.session, p.itemId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "protected sharers not read", e)
                emptyList() // the data layer still leaves every protected profile out without its PIN
            }
            asking = Asking(p, ArrayDeque(locked))
            askNext()
        }
    }

    fun submitSharerPin() {
        val a = asking ?: return
        val ask = _sharerPin.value ?: return
        if (ask.busy) return
        val sharer = a.queue.first()
        val chars = CharArray(pin.text.length) { pin.text[it] }
        pin.clearText()
        _sharerPin.value = ask.copy(busy = true)
        scope.launch {
            val check = try {
                housekeeping.proveSharer(a.prompt.session, sharer.userId, a.prompt.itemId, chars)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.w(TAG, "a sharer's PIN not checked", e)
                null
            } finally {
                chars.fill('\u0000')
            }
            if (check == PinCheck.Correct) {
                a.queue.removeFirst()
                askNext()
            } else {
                _sharerPin.value = ask.copy(answer = check, busy = false)
            }
        }
    }

    fun cancelSharerPin() {
        val a = asking ?: return
        pin.clearText()
        a.queue.removeFirstOrNull()
        a.leftOut = true
        askNext()
    }

    private fun askNext() {
        val a = asking ?: return
        val next = a.queue.firstOrNull()
        if (next != null) {
            _sharerPin.value = SharerPinAsk(next.userName)
            return
        }
        _sharerPin.value = null
        asking = null
        release(a.prompt, together = true, leftOut = a.leftOut)
    }

    private fun release(together: Boolean) {
        val p = _prompt.value ?: return
        if (p.always) return
        _prompt.value = null // one press only
        release(p, together, leftOut = false)
    }

    private fun release(p: WatchedPromptUi, together: Boolean, leftOut: Boolean) {
        scope.launch {
            val message = try {
                val outcome = when (housekeeping.release(p.session, p.itemId, together)) {
                    ReleaseOutcome.DELETED -> WatchedMessage.DELETED
                    ReleaseOutcome.KEPT_FOR_OTHERS -> WatchedMessage.KEPT_FOR_OTHERS
                    ReleaseOutcome.KEPT_UNTIL_EVERYONE_WATCHED -> WatchedMessage.KEPT_UNTIL_EVERYONE
                    ReleaseOutcome.NOT_YOURS -> WatchedMessage.FAILED
                }
                if (leftOut && outcome != WatchedMessage.FAILED) WatchedMessage.LEFT_OUT else outcome
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
