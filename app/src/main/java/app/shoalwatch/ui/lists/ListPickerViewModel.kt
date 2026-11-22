package app.shoalwatch.ui.lists

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.R
import app.shoalwatch.data.lists.Lists
import app.shoalwatch.data.lists.ListsLimits
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.Session
import app.shoalwatch.ui.browse.BulkItem
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

/** What the picker adds to. */
enum class ListKind { PLAYLIST, COLLECTION }

/** An existing playlist or collection the items can go into. */
data class ListChoice(val id: String, val name: String) {
    override fun toString() = "ListChoice(<redacted>)"
}

/** The picker as it stands: closed while [kind] is null. [itemCount] items (ids held in the ViewModel) are waiting to go in. */
data class PickerUiState(
    val session: Session? = null,
    val kind: ListKind? = null,
    val itemCount: Int = 0,
    val phase: ListsPhase = ListsPhase.LOADING,
    val choices: List<ListChoice> = emptyList(),
    /** The New playlist / New collection form is showing instead of the list. */
    val creating: Boolean = false,
    /** What is wrong with the name or the server's answer (a string resource, 0 for nothing). */
    val error: Int = 0,
    val busy: Boolean = false,
) {
    val open: Boolean get() = kind != null
    override fun toString() = "PickerUiState(<redacted>)"
}

/**
 * The add-to picker (Milestone 11, decision 8): one dialog's state for "Add to playlist" and "Add to collection". It reads the
 * profile's playlists or collections live when opened, adds the picked items to the chosen one, or creates a new one holding
 * them (name: 1 to 200 visible characters, checked here with specific words before anything is sent). A refusal or failure stays
 * in the dialog as words; success closes it with one line. At most [ListsLimits.IDS_PER_CALL] items go in at once. Closed
 * when the session changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ListPickerViewModel(catalog: ServerCatalog, private val lists: Lists) : ViewModel() {
    private val body = MutableStateFlow(PickerUiState())
    private val channel = Channel<ListNotice>(Channel.BUFFERED)
    private val sessionNow: StateFlow<Session?> = catalog.activeSession().stateIn(viewModelScope, SharingStarted.Eagerly, null)
    private var itemIds: List<String> = emptyList()

    val state: StateFlow<PickerUiState> = body.asStateFlow()

    /** The name typed for a new list. */
    val name = TextFieldState()

    val notices: Flow<ListNotice> = channel.receiveAsFlow().filter { it.session == sessionNow.value }

    init {
        viewModelScope.launch {
            catalog.activeSession().collectLatest { s ->
                itemIds = emptyList()
                name.clearText()
                body.value = PickerUiState(session = s)
            }
        }
    }

    /** Opens the picker for [items] (each once, in order). Nothing opens for none, or for more than one call can carry (said in words). */
    fun open(items: List<BulkItem>, kind: ListKind) {
        val s = sessionNow.value ?: return
        val ids = items.map { it.id }.distinct()
        if (ids.isEmpty()) return
        if (ids.size > ListsLimits.IDS_PER_CALL) {
            channel.trySend(ListNotice(s, text = R.string.lists_too_many))
            return
        }
        itemIds = ids
        name.clearText()
        body.value = PickerUiState(session = s, kind = kind, itemCount = ids.size)
        viewModelScope.launch { read(s, kind) }
    }

    private suspend fun read(s: Session, kind: ListKind) {
        try {
            val found = if (kind == ListKind.PLAYLIST) lists.playlists(s) else lists.collections(s)
            val choices = found.map { ListChoice(it.item.id, it.item.name) }
            apply(s, kind) { it.copy(phase = ListsPhase.READY, choices = choices) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            apply(s, kind) { it.copy(phase = phaseFor(e)) }
        }
    }

    private fun apply(s: Session, kind: ListKind, change: (PickerUiState) -> PickerUiState) =
        body.update { if (it.session == s && it.kind == kind) change(it) else it }

    fun retry() {
        val s = body.value.session ?: return
        val kind = body.value.kind ?: return
        body.update { it.copy(phase = ListsPhase.LOADING, error = 0) }
        viewModelScope.launch { read(s, kind) }
    }

    fun startCreate() {
        name.clearText()
        body.update { it.copy(creating = true, error = 0) }
    }

    fun backToList() {
        if (body.value.busy) return
        body.update { it.copy(creating = false, error = 0) }
    }

    fun dismiss() {
        if (body.value.busy) return
        itemIds = emptyList()
        name.clearText()
        body.update { PickerUiState(session = it.session) }
    }

    /** Adds the items to the existing list [choice]; one line on success, words in the dialog otherwise. */
    fun choose(choice: ListChoice) {
        val s = body.value.session ?: return
        val kind = body.value.kind ?: return
        if (body.value.busy || itemIds.isEmpty()) return
        val ids = itemIds
        body.update { it.copy(busy = true, error = 0) }
        viewModelScope.launch {
            try {
                if (kind == ListKind.PLAYLIST) lists.addToPlaylist(s, choice.id, ids) else lists.addToCollection(s, choice.id, ids)
                channel.trySend(
                    ListNotice(s, if (kind == ListKind.PLAYLIST) R.plurals.lists_added_playlist else R.plurals.lists_added_collection, ids.size, arg = choice.name),
                )
                finish(s, kind)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                apply(s, kind) { it.copy(busy = false, error = failureText(e)) }
            }
        }
    }

    /** Checks the name, creates a new list holding the items, one line on success. */
    fun create() {
        val s = body.value.session ?: return
        val kind = body.value.kind ?: return
        if (body.value.busy || itemIds.isEmpty()) return
        val clean = when (val c = cleanListName(name.text.toString())) {
            is ListName.Valid -> c.text
            else -> {
                body.update { it.copy(error = c.problem()) }
                return
            }
        }
        val ids = itemIds
        body.update { it.copy(busy = true, error = 0) }
        viewModelScope.launch {
            try {
                if (kind == ListKind.PLAYLIST) lists.createPlaylist(s, clean, ids) else lists.createCollection(s, clean, ids)
                channel.trySend(
                    ListNotice(s, if (kind == ListKind.PLAYLIST) R.plurals.lists_created_playlist else R.plurals.lists_created_collection, ids.size, arg = clean),
                )
                finish(s, kind)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                apply(s, kind) { it.copy(busy = false, error = failureText(e)) }
            }
        }
    }

    private fun finish(s: Session, kind: ListKind) {
        body.update { if (it.session == s && it.kind == kind) PickerUiState(session = s) else it }
        if (body.value.session == s && !body.value.open) {
            itemIds = emptyList()
            name.clearText()
        }
    }
}
