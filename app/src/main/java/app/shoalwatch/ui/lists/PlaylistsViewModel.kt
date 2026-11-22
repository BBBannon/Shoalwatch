package app.shoalwatch.ui.lists

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.R
import app.shoalwatch.data.library.ItemWithUserData
import app.shoalwatch.data.lists.Lists
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlaylistsUiState(
    /** The profile every list below belongs to; null while nobody is signed in. */
    val session: Session? = null,
    val phase: ListsPhase = ListsPhase.LOADING,
    val playlists: List<ItemWithUserData> = emptyList(),
    /** The "New playlist" window is open. */
    val creating: Boolean = false,
    /** What is wrong with the name or the server's answer to Create (a string resource, 0 for nothing). */
    val nameError: Int = 0,
    val busy: Boolean = false,
) {
    override fun toString() = "PlaylistsUiState(<redacted>)"
}

/**
 * The profile's playlists (Milestone 11, live: read each time the screen opens and after each change). Restarts when the
 * session changes and nothing of the old one lingers. A new playlist is named here first (1 to 200 visible characters, with
 * specific words) before anything is sent, then the list is read again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistsViewModel(catalog: ServerCatalog, private val lists: Lists) : ViewModel() {
    private val body = MutableStateFlow(PlaylistsUiState())
    private val channel = Channel<ListNotice>(Channel.BUFFERED)
    private val sessionNow: StateFlow<Session?> = catalog.activeSession().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<PlaylistsUiState> = body.asStateFlow()

    /** The name typed in the New playlist window. */
    val name = TextFieldState()

    /** Snackbar lines, each once, only while their session is the one on screen. */
    val notices: Flow<ListNotice> = channel.receiveAsFlow().filter { it.session == sessionNow.value }

    init {
        viewModelScope.launch {
            catalog.activeSession().collectLatest { s ->
                name.clearText()
                body.value = PlaylistsUiState(session = s, phase = if (s == null) ListsPhase.READY else ListsPhase.LOADING)
                if (s != null) read(s)
            }
        }
    }

    private suspend fun read(s: Session) {
        try {
            val items = lists.playlists(s)
            apply(s) { it.copy(phase = ListsPhase.READY, playlists = items) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            apply(s) { it.copy(phase = phaseFor(e), playlists = emptyList()) }
        }
    }

    private fun apply(s: Session, change: (PlaylistsUiState) -> PlaylistsUiState) = body.update { if (it.session == s) change(it) else it }

    /** Back on this screen after a change made elsewhere (a playlist deleted or renamed, items added): read again, without a spinner. */
    fun refresh() {
        val s = body.value.session ?: return
        if (body.value.phase == ListsPhase.LOADING) return
        viewModelScope.launch { read(s) }
    }

    /** Try again (offline or failed). */
    fun retry() {
        val s = body.value.session ?: return
        body.update { it.copy(phase = ListsPhase.LOADING) }
        viewModelScope.launch { read(s) }
    }

    fun startCreate() {
        name.clearText()
        body.update { it.copy(creating = true, nameError = 0) }
    }

    fun cancelCreate() {
        if (body.value.busy) return
        body.update { it.copy(creating = false, nameError = 0) }
        name.clearText()
    }

    /** Checks the name, creates an empty private playlist once, then reads the list again. */
    fun create() {
        val s = body.value.session ?: return
        if (body.value.busy) return
        val clean = when (val c = cleanListName(name.text.toString())) {
            is ListName.Valid -> c.text
            else -> {
                body.update { it.copy(nameError = c.problem()) }
                return
            }
        }
        body.update { it.copy(busy = true, nameError = 0) }
        viewModelScope.launch {
            try {
                lists.createPlaylist(s, clean, emptyList())
                apply(s) { it.copy(creating = false) }
                name.clearText()
                channel.trySend(ListNotice(s, text = R.string.lists_created, arg = clean))
                read(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                apply(s) { it.copy(nameError = failureText(e)) }
            } finally {
                apply(s) { it.copy(busy = false) }
            }
        }
    }
}
