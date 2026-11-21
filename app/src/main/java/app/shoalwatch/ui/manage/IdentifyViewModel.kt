package app.shoalwatch.ui.manage

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.viewModelScope
import app.shoalwatch.R
import app.shoalwatch.data.admin.AdminRights
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.library.Library
import app.shoalwatch.data.manage.IdentifyKind
import app.shoalwatch.data.manage.IdentifyMatch
import app.shoalwatch.data.manage.Manage
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.ui.admin.AdminFrame
import app.shoalwatch.ui.admin.AdminGate
import app.shoalwatch.ui.admin.AdminNotice
import app.shoalwatch.ui.admin.AdminScreenModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

/** The match waiting for a yes, and "Replace all pictures" (on by default, as the web dashboard). */
data class ApplyAsk(val match: IdentifyMatch, val replaceImages: Boolean = true) {
    override fun toString() = "ApplyAsk(<redacted>)"
}

data class IdentifyUiState(
    val frame: AdminFrame = AdminFrame(),
    /** Null until the item is known; then MOVIE or SERIES, or null for a kind Identify does not offer. */
    val kind: IdentifyKind? = null,
    val known: Boolean = false,
    /** Null: no search yet. */
    val results: List<IdentifyMatch>? = null,
    val apply: ApplyAsk? = null,
) {
    override fun toString() = "IdentifyUiState(<redacted>)"
}

/**
 * Identify (Milestone 10, plan decision 12): the title and year start as the item's own; Search asks the server's providers
 * (`RemoteSearch/Movie` or `/Series`), and the matches are listed in words (their pictures are third-party addresses the phone
 * never loads, S7). Use this asks first, then applies the match once, and the item is fetched again.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IdentifyViewModel(
    private val itemId: String,
    catalog: ServerCatalog,
    rights: AdminRights,
    connections: ServerConnections,
    private val manage: Manage,
    private val library: Library,
    private val after: AfterChange,
    clock: () -> Instant = Instant::now,
) : AdminScreenModel(catalog, rights, connections, clock) {
    val name = TextFieldState()
    val year = TextFieldState()
    private val kind = MutableStateFlow<IdentifyKind?>(null)
    private val known = MutableStateFlow(false)
    private val results = MutableStateFlow<List<IdentifyMatch>?>(null)
    private val apply = MutableStateFlow<ApplyAsk?>(null)

    val state: StateFlow<IdentifyUiState> = combine(frames, kind, known, results, apply) { frame, k, kn, r, a ->
        if (frame.gate == AdminGate.Closed) IdentifyUiState(frame = frame) else IdentifyUiState(frame, k, kn, r, a)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, IdentifyUiState())

    init {
        // The fields start as the item's own name and year, once, as soon as the gate is open.
        viewModelScope.launch {
            val s = frames.map { (it.gate as? AdminGate.Open)?.session }.filterNotNull().first()
            val shown = library.item(s, itemId).map { it.item }.catch { emit(null) }.filterNotNull().first()
            kind.value = when (shown.item.kind) {
                ItemKind.MOVIE -> IdentifyKind.MOVIE
                ItemKind.SERIES -> IdentifyKind.SERIES
                else -> null
            }
            known.value = true
            if (name.text.isEmpty()) name.setTextAndPlaceCursorAtEnd(shown.item.name)
            if (year.text.isEmpty()) shown.item.year?.let { year.setTextAndPlaceCursorAtEnd(it.toString()) }
        }
    }

    /** The typed year, if it is one (blank or anything else: no year). */
    private fun typedYear(): Int? = year.text.toString().trim().toIntOrNull()?.takeIf { it in 1800..2200 }

    fun search() {
        val k = kind.value ?: return
        val term = name.text.toString().trim()
        if (term.isEmpty()) return
        val y = typedYear()
        work.run("search", { s ->
            val found = manage.identify(s, itemId, k, term, y)
            if (frames.value.gate is AdminGate.Open) results.value = found
        })
    }

    fun askApply(match: IdentifyMatch) {
        if (frames.value.canAct && results.value?.contains(match) == true) apply.value = ApplyAsk(match)
    }

    fun setReplaceImages(on: Boolean) = apply.update { it?.copy(replaceImages = on) }

    fun dismissApply() {
        apply.value = null
    }

    fun confirmApply() {
        val ask = apply.value ?: return
        apply.value = null
        work.run("apply", { s ->
            manage.applyMatch(s, itemId, ask.match, ask.replaceImages)
            after.changed(s, itemId)
        }) { notify(AdminNotice(R.string.identify_applied)) }
    }

    override fun dropLoaded() {
        results.value = null
        apply.value = null
        name.clearText()
        year.clearText()
    }
}
