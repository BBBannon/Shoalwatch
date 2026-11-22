package app.shoalwatch.ui.lists

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.R
import app.shoalwatch.data.downloads.DownloadQuality
import app.shoalwatch.data.downloads.DownloadTarget
import app.shoalwatch.data.downloads.Downloads
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.lists.Lists
import app.shoalwatch.data.lists.Playlist
import app.shoalwatch.data.playback.PlayQueue
import app.shoalwatch.data.playback.PlayQueues
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

data class PlaylistUiState(
    val session: Session? = null,
    val phase: ListsPhase = ListsPhase.LOADING,
    val playlist: Playlist? = null,
    /** A change or the download is running: the buttons wait. */
    val busy: Boolean = false,
    val renaming: Boolean = false,
    val nameError: Int = 0,
    /** Delete is waiting for a yes (the dialog names the playlist). */
    val deleteAsk: Boolean = false,
    /** This playlist's own delete is running (not any other change). */
    val deleting: Boolean = false,
    /** The playlist was deleted: the route leaves the screen. */
    val deleted: Boolean = false,
) {
    override fun toString() = "PlaylistUiState(<redacted>)"
}

/** Play was pressed: the player route for [itemId] carries the queue's [token]. */
data class PlayLaunch(val session: Session, val itemId: String, val token: String) {
    override fun toString() = "PlayLaunch(<redacted>)"
}

/**
 * One playlist (Milestone 11, live). Play and Shuffle hold the playable entries as a [PlayQueue] under a token the player route
 * carries; Download all prepares and starts each film or episode at the default quality; Move up and Move down, Remove, Rename
 * and Delete change it on the server once each, and the list is read again afterwards whatever happened. A refusal is the plan's
 * one sentence and never signs out (the seam already checked). Restarts when the session changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistViewModel(
    private val playlistId: String,
    catalog: ServerCatalog,
    private val lists: Lists,
    private val queues: PlayQueues,
    private val downloads: Downloads?,
) : ViewModel() {
    private val body = MutableStateFlow(PlaylistUiState())
    private val channel = Channel<ListNotice>(Channel.BUFFERED)
    private val launchChannel = Channel<PlayLaunch>(Channel.BUFFERED)
    private var launched = false
    private val sessionNow: StateFlow<Session?> = catalog.activeSession().stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val state: StateFlow<PlaylistUiState> = body.asStateFlow()

    /** The name typed in the Rename window. */
    val name = TextFieldState()

    val notices: Flow<ListNotice> = channel.receiveAsFlow().filter { it.session == sessionNow.value }

    /** Each Play or Shuffle, once, only while its session is the one on screen. */
    val launches: Flow<PlayLaunch> = launchChannel.receiveAsFlow().filter { it.session == sessionNow.value }

    init {
        viewModelScope.launch {
            catalog.activeSession().collectLatest { s ->
                name.clearText()
                body.value = PlaylistUiState(session = s, phase = if (s == null) ListsPhase.READY else ListsPhase.LOADING)
                if (s != null) read(s)
            }
        }
    }

    private suspend fun read(s: Session) {
        try {
            val p = lists.playlist(s, playlistId)
            apply(s) { it.copy(phase = ListsPhase.READY, playlist = p) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            apply(s) { it.copy(phase = phaseFor(e), playlist = null) }
        }
    }

    private fun apply(s: Session, change: (PlaylistUiState) -> PlaylistUiState) = body.update { if (it.session == s) change(it) else it }

    fun retry() {
        val s = body.value.session ?: return
        body.update { it.copy(phase = ListsPhase.LOADING) }
        viewModelScope.launch { read(s) }
    }

    /** Play (in order) or Shuffle (shuffled once, here): the playable entries become a queue and the player opens on its first. */
    fun play(shuffle: Boolean) {
        val s = body.value.session ?: return
        val p = body.value.playlist ?: return
        if (launched) return
        val queue = PlayQueue.of(p.entries.map { it.item.item }, shuffle)
        val first = queue?.ids?.firstOrNull()
        if (queue == null || first == null) {
            channel.trySend(ListNotice(s, text = R.string.lists_nothing_to_play))
            return
        }
        launched = true
        launchChannel.trySend(PlayLaunch(s, first, queues.put(s, queue)))
    }

    /** The screen is on top again (back from the player): Play and Shuffle work again. A double tap opens the player once. */
    fun onResumed() { launched = false }

    /** Each film or episode through prepare and start at the default quality; one line sums it up. */
    fun downloadAll() {
        val s = body.value.session ?: return
        val p = body.value.playlist ?: return
        val ids = p.entries.map { it.item.item }.filter { it.kind == ItemKind.MOVIE || it.kind == ItemKind.EPISODE }.map { it.id }.distinct()
        val d = downloads
        if (ids.isEmpty() || d == null) {
            channel.trySend(ListNotice(s, text = R.string.lists_nothing_to_download))
            return
        }
        if (body.value.busy) return
        body.update { it.copy(busy = true) }
        viewModelScope.launch {
            var started = 0
            var firstFailure: Exception? = null
            try {
                for (id in ids) {
                    if (sessionNow.value != s) break
                    try {
                        val plan = d.prepare(s, DownloadTarget.Item(id), d.settings.value.defaultQuality ?: DownloadQuality.ORIGINAL)
                        if (plan.enoughSpace && plan.items.isNotEmpty()) {
                            d.start(s, plan)
                            started++
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        if (firstFailure == null) firstFailure = e
                    }
                }
            } finally {
                apply(s) { it.copy(busy = false) }
            }
            val rest = ids.size - started
            when {
                started > 0 && rest > 0 -> channel.trySend(ListNotice(s, R.plurals.lists_downloading_some, started, arg = ids.size.toString()))
                started > 0 -> channel.trySend(ListNotice(s, R.plurals.lists_downloading, started))
                firstFailure != null -> channel.trySend(ListNotice(s, text = failureText(firstFailure)))
                else -> channel.trySend(ListNotice(s, R.plurals.lists_download_not_started, rest))
            }
        }
    }

    /** One place earlier ([delta] -1) or later (+1); nothing at either end. */
    fun move(entryId: String, delta: Int) {
        val s = body.value.session ?: return
        val entries = body.value.playlist?.entries ?: return
        val index = entries.indexOfFirst { it.entryId == entryId }
        val target = index + delta
        if (index < 0 || target !in entries.indices) return
        change(s) { lists.moveInPlaylist(s, playlistId, entryId, target) }
    }

    fun remove(entryId: String) {
        val s = body.value.session ?: return
        val entry = body.value.playlist?.entries?.firstOrNull { it.entryId == entryId } ?: return
        change(s) {
            lists.removeFromPlaylist(s, playlistId, listOf(entryId))
            channel.trySend(ListNotice(s, text = R.string.lists_removed, arg = entry.item.item.name))
        }
    }

    fun startRename() {
        val p = body.value.playlist ?: return
        name.setTextAndPlaceCursorAtEnd(p.item.name)
        body.update { it.copy(renaming = true, nameError = 0) }
    }

    fun cancelRename() {
        if (body.value.busy) return
        body.update { it.copy(renaming = false, nameError = 0) }
        name.clearText()
    }

    /** Checks the name (1 to 200 visible characters), renames once, reads the list again. */
    fun rename() {
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
            var failure: Exception? = null
            try {
                lists.renamePlaylist(s, playlistId, clean)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
            if (failure == null) {
                apply(s) { it.copy(renaming = false) }
                name.clearText()
            } else {
                apply(s) { it.copy(nameError = failureText(failure)) }
            }
            read(s)
            apply(s) { it.copy(busy = false) }
        }
    }

    /** Delete asks first; only a playlist the server says this profile may delete ([Playlist.canDelete]) is asked about. */
    fun askDelete() {
        if (body.value.playlist?.canDelete != true || body.value.busy) return
        body.update { it.copy(deleteAsk = true) }
    }

    fun cancelDelete() = body.update { if (it.busy) it else it.copy(deleteAsk = false) }

    fun confirmDelete() {
        val s = body.value.session ?: return
        if (!body.value.deleteAsk || body.value.busy) return
        body.update { it.copy(busy = true, deleting = true) }
        viewModelScope.launch {
            try {
                lists.deletePlaylist(s, playlistId)
                apply(s) { it.copy(deleted = true, deleteAsk = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                apply(s) { it.copy(deleteAsk = false) }
                channel.trySend(ListNotice(s, text = failureText(e)))
                read(s) // CanDelete may have changed
            } finally {
                apply(s) { it.copy(busy = false, deleting = false) }
            }
        }
    }

    /** Runs one change as [s], then reads the playlist again whatever happened (a failure says why). One at a time. */
    private fun change(s: Session, act: suspend () -> Unit) {
        if (body.value.busy) return
        body.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                act()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                channel.trySend(ListNotice(s, text = failureText(e)))
            }
            read(s)
            apply(s) { it.copy(busy = false) }
        }
    }
}
