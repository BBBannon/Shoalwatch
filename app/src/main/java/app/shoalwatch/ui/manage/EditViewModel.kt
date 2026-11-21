package app.shoalwatch.ui.manage

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.viewModelScope
import app.shoalwatch.R
import app.shoalwatch.data.admin.AdminRights
import app.shoalwatch.data.manage.LockableField
import app.shoalwatch.data.manage.Manage
import app.shoalwatch.data.manage.ManageLimits
import app.shoalwatch.data.manage.MetadataEdit
import app.shoalwatch.data.manage.MetadataForm
import app.shoalwatch.data.servers.ServerCatalog
import app.shoalwatch.data.servers.ServerConnections
import app.shoalwatch.ui.admin.AdminFrame
import app.shoalwatch.ui.admin.AdminGate
import app.shoalwatch.ui.admin.AdminNotice
import app.shoalwatch.ui.admin.AdminScreenModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** What is wrong with the form, said beside the field. */
enum class EditProblem { NAME, YEAR, DATE, RATING }

/** The three lists, each edited entry by entry (an entry may hold commas: "Lions Gate Films, Inc."). */
enum class EditList { GENRES, TAGS, STUDIOS }

data class EditUiState(
    val frame: AdminFrame = AdminFrame(),
    val loaded: Boolean = false,
    val ratingOptions: List<String> = emptyList(),
    val locked: Set<LockableField> = emptySet(),
    val lockAll: Boolean = false,
    val problems: Set<EditProblem> = emptySet(),
    val lists: Map<EditList, List<String>> = emptyMap(),
) {
    override fun toString() = "EditUiState(<redacted>)"
}

/**
 * Edit details (Milestone 10, plan decision 10). The form is read once (`MetadataEditor` and the item) into fields held only
 * here; Save checks the year (1800 to 2200), the date (year-month-day) and the rating (0 to 10) on the phone, then sends typed
 * values once over the server's own item. Lists are edited entry by entry (chips: add and remove), never split on commas. The form
 * as first shown is kept ([shown]), the rating's shown text with it: a field left as shown is sent back as the server's own value,
 * and the gateway writes only the fields whose value differs from it, so saving an untouched form changes nothing.
 */
class EditViewModel(
    private val itemId: String,
    catalog: ServerCatalog,
    rights: AdminRights,
    connections: ServerConnections,
    private val manage: Manage,
    private val after: AfterChange,
    clock: () -> Instant = Instant::now,
) : AdminScreenModel(catalog, rights, connections, clock) {
    val name = TextFieldState()
    val sortName = TextFieldState()
    val originalTitle = TextFieldState()
    val overview = TextFieldState()
    val year = TextFieldState()
    val premiere = TextFieldState()
    val officialRating = TextFieldState()
    val communityRating = TextFieldState()
    /** What is being typed for each list before Add. */
    val inputs: Map<EditList, TextFieldState> = EditList.entries.associateWith { TextFieldState() }
    private val fields get() = listOf(name, sortName, originalTitle, overview, year, premiere, officialRating, communityRating) + inputs.values
    private val lists = MutableStateFlow<Map<EditList, List<String>>>(emptyMap())
    /** The form as the server gave it, and the rating's text as shown from it. */
    private var shown: MetadataForm? = null
    private var shownRating = ""

    private val loaded = MutableStateFlow(false)
    private val options = MutableStateFlow<List<String>>(emptyList())
    private val locked = MutableStateFlow<Set<LockableField>>(emptySet())
    private val lockAll = MutableStateFlow(false)
    private val problems = MutableStateFlow<Set<EditProblem>>(emptySet())

    private val poll = poller { s ->
        if (!loaded.value) {
            val form = manage.metadata(s, itemId)
            if (guard.gate.value is AdminGate.Open) fill(form)
        }
        null
    }

    val state: StateFlow<EditUiState> = combine(frames, loaded, options, combine(locked, lockAll, lists) { l, a, e -> Triple(l, a, e) }, problems) { frame, ok, o, l, p ->
        if (frame.gate == AdminGate.Closed) EditUiState(frame = frame) else EditUiState(frame, ok, o, l.first, l.second, p, l.third)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, EditUiState())

    private fun fill(f: MetadataForm) {
        name.setTextAndPlaceCursorAtEnd(f.name)
        sortName.setTextAndPlaceCursorAtEnd(f.sortName)
        originalTitle.setTextAndPlaceCursorAtEnd(f.originalTitle)
        overview.setTextAndPlaceCursorAtEnd(f.overview)
        year.setTextAndPlaceCursorAtEnd(f.year?.toString().orEmpty())
        premiere.setTextAndPlaceCursorAtEnd(f.premiereDate?.toString().orEmpty())
        officialRating.setTextAndPlaceCursorAtEnd(f.officialRating)
        shownRating = f.communityRating?.let { String.format(java.util.Locale.UK, "%.1f", it) }.orEmpty()
        communityRating.setTextAndPlaceCursorAtEnd(shownRating)
        inputs.values.forEach { it.clearText() }
        lists.value = mapOf(EditList.GENRES to f.genres, EditList.TAGS to f.tags, EditList.STUDIOS to f.studios)
        shown = f
        options.value = f.ratingOptions
        locked.value = f.locked
        lockAll.value = f.lockAll
        loaded.value = true
    }

    fun refresh() {
        loaded.value = false
        poll.kick()
    }

    fun setLocked(field: LockableField, on: Boolean) = locked.update { if (on) it + field else it - field }

    fun setLockAll(on: Boolean) {
        lockAll.value = on
    }

    /** Adds what is typed for [list] as ONE entry (commas and all), unless it is blank or already there. */
    fun addEntry(list: EditList) {
        val input = inputs.getValue(list)
        val typed = input.text.toString().trim()
        input.clearText()
        if (typed.isEmpty()) return
        lists.update { all ->
            val now = all[list].orEmpty()
            if (now.any { it.equals(typed, ignoreCase = true) } || now.size >= ManageLimits.LIST_SIZE) all else all + (list to now + typed)
        }
    }

    fun removeEntry(list: EditList, index: Int) = lists.update { all ->
        val now = all[list].orEmpty()
        if (index !in now.indices) all else all + (list to now.filterIndexed { i, _ -> i != index })
    }

    /** The form as an edit, or the problems that stop it being sent. */
    internal fun edit(): Pair<MetadataEdit?, Set<EditProblem>> {
        val found = HashSet<EditProblem>()
        val n = name.text.toString().trim()
        if (n.isEmpty()) found += EditProblem.NAME
        val y = year.text.toString().trim().let { t -> if (t.isEmpty()) null else t.toIntOrNull()?.takeIf { it in 1800..2200 } ?: run { found += EditProblem.YEAR; null } }
        val d = premiere.text.toString().trim().let { t ->
            if (t.isEmpty()) null else try {
                LocalDate.parse(t).takeIf { it.year in 1800..2200 } ?: run { found += EditProblem.DATE; null }
            } catch (e: DateTimeParseException) {
                found += EditProblem.DATE
                null
            }
        }
        val ratingText = communityRating.text.toString().trim()
        val r = if (ratingText == shownRating) shown?.communityRating else ratingText.replace(',', '.').let { t ->
            if (t.isEmpty()) null else t.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..10.0 } ?: run { found += EditProblem.RATING; null }
        }
        if (found.isNotEmpty()) return null to found
        // An entry typed but not yet added still counts (as one entry).
        fun list(l: EditList) = lists.value[l].orEmpty() + listOfNotNull(inputs.getValue(l).text.toString().trim().takeIf { it.isNotEmpty() })
        return MetadataEdit(
            name = n, sortName = sortName.text.toString().trim(), originalTitle = originalTitle.text.toString().trim(),
            overview = overview.text.toString(), year = y, premiereDate = d, officialRating = officialRating.text.toString().trim(),
            communityRating = r, genres = list(EditList.GENRES), tags = list(EditList.TAGS), studios = list(EditList.STUDIOS),
            locked = locked.value, lockAll = lockAll.value,
        ) to emptySet()
    }

    fun save() {
        val was = shown
        if (!loaded.value || was == null) return
        val (edit, found) = edit()
        problems.value = found
        if (edit == null) return
        work.run("save", { s ->
            manage.saveMetadata(s, itemId, edit, was)
            after.changed(s, itemId)
        }) { notify(AdminNotice(R.string.edit_saved)) }
    }

    override fun dropLoaded() {
        fields.forEach { it.clearText() }
        loaded.value = false
        options.value = emptyList()
        locked.value = emptySet()
        lockAll.value = false
        problems.value = emptySet()
        lists.value = emptyMap()
        shown = null
        shownRating = ""
    }
}
