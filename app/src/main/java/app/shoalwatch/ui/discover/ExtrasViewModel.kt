package app.shoalwatch.ui.discover

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.shoalwatch.core.AppError
import app.shoalwatch.data.discover.Discover
import app.shoalwatch.data.discover.Extras
import app.shoalwatch.data.lists.CollectionRef
import app.shoalwatch.data.lists.Lists
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** LOADING: the first read is running. OFFLINE: every part failed because the server can't be reached (Try again). READY: show what arrived; each part hides on its own when empty. */
enum class ExtrasPhase { LOADING, READY, OFFLINE }

data class ExtrasUiState(
    /** The profile every part below belongs to; null while nobody is signed in. */
    val session: Session? = null,
    val phase: ExtrasPhase = ExtrasPhase.LOADING,
    val extras: Extras = Extras.NONE,
    val collections: List<CollectionRef> = emptyList(),
    /** The version picked for the next play only; null is the server's first. */
    val chosenVersionId: String? = null,
) {
    /** The version the page shows as chosen: the pick if it is still one of the versions, else the first. */
    val shownVersionId: String? get() = extras.versions.firstOrNull { it.id == chosenVersionId }?.id ?: extras.versions.firstOrNull()?.id

    override fun toString() = "ExtrasUiState(<redacted>)"
}

/**
 * The extras block of a detail or series page (Milestone 11, live): cast and crew, trailers, extras, more like this, the
 * collections the item is part of, and the versions of a film. Read when the page opens (per profile; nothing of another one
 * lingers), read again on Try again; "Part of this collection" is read again each time the page comes back, as an add or remove
 * made elsewhere changes it. Each part hides on its own when empty or failed; only when every part failed because the server
 * can't be reached does the block say "You're offline".
 */
class ExtrasViewModel(private val itemId: String, catalog: ServerCatalog, private val discover: Discover, private val lists: Lists) : ViewModel() {
    private val body = MutableStateFlow(ExtrasUiState())
    val state: StateFlow<ExtrasUiState> = body.asStateFlow()

    /** A tap that leaves the page (a trailer, the player) works once until the page is on top again, so a double tap opens it once. */
    private var launched = false

    init {
        viewModelScope.launch {
            catalog.activeSession().collectLatest { s ->
                launched = false
                body.value = ExtrasUiState(session = s, phase = if (s == null) ExtrasPhase.READY else ExtrasPhase.LOADING)
                if (s != null) read(s)
            }
        }
    }

    private suspend fun read(s: Session) {
        val (extras, collections) = coroutineScope {
            val e = async { runCatching { discover.extras(s, itemId) } }
            val c = async { runCatching { lists.collectionsOf(s, itemId) } }
            e.await() to c.await()
        }
        val failure = extras.exceptionOrNull()
        if (failure is CancellationException) throw failure
        val phase = if (failure != null && phaseOf(failure) == ExtrasPhase.OFFLINE) ExtrasPhase.OFFLINE else ExtrasPhase.READY
        body.update {
            if (it.session != s) it
            else it.copy(phase = phase, extras = extras.getOrDefault(Extras.NONE), collections = if (phase == ExtrasPhase.OFFLINE) emptyList() else collections.getOrDefault(emptyList()))
        }
    }

    private fun phaseOf(e: Throwable) = when ((e as? AppError)?.kind) {
        AppError.Kind.OFFLINE, AppError.Kind.NETWORK, AppError.Kind.TIMEOUT -> ExtrasPhase.OFFLINE
        else -> ExtrasPhase.READY
    }

    /** Try again after "You're offline". */
    fun retry() {
        val s = body.value.session ?: return
        body.update { it.copy(phase = ExtrasPhase.LOADING) }
        viewModelScope.launch { read(s) }
    }

    /** The page is on top again (back from the player, the picker or another page): taps work again and the collections are read again. */
    fun onResumed() {
        launched = false
        val s = body.value.session ?: return
        if (body.value.phase != ExtrasPhase.READY) return
        viewModelScope.launch {
            val now = runCatching { lists.collectionsOf(s, itemId) }.getOrNull() ?: return@launch
            body.update { if (it.session == s && it.phase == ExtrasPhase.READY) it.copy(collections = now) else it }
        }
    }

    /** Picks the version for the next play. Anything that is not one of the versions is ignored. */
    fun chooseVersion(id: String) {
        body.update { s -> if (s.extras.versions.any { it.id == id }) s.copy(chosenVersionId = id) else s }
    }

    /**
     * The version to play, once: the pick (cleared by this, so it lasts for that play only), or null for the server's first.
     * Called as Play is pressed.
     */
    fun takeVersion(): String? {
        val chosen = body.value.let { s -> s.chosenVersionId?.takeIf { id -> s.extras.versions.any { it.id == id } } }
        if (body.value.chosenVersionId != null) body.update { it.copy(chosenVersionId = null) }
        return chosen
    }

    /** Lets the next tap through again (the other app was not there, so the page never left). */
    fun release() { launched = false }

    /** Runs [action] unless a tap already did and the page has not come back on top since. */
    fun once(action: () -> Unit) {
        if (launched) return
        launched = true
        action()
    }
}
