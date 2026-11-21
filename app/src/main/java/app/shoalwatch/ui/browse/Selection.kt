package app.shoalwatch.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.servers.Session

/**
 * The items picked in selection mode on one screen (Milestone 10). A long press on a card starts it; while it is [active] a tap
 * adds or removes; Back or Close clears it. Ids only, in the order they were picked, saved across rotation and kept per profile
 * ([rememberSelection] is keyed by the session, so a profile change starts empty).
 */
@Stable
class Selection internal constructor(initial: List<String>) {
    var ids: List<String> by mutableStateOf(initial)
        private set

    val active: Boolean get() = ids.isNotEmpty()
    val count: Int get() = ids.size

    operator fun contains(id: String): Boolean = id in ids

    /** Adds [id], or removes it if it is already picked. */
    fun toggle(id: String) {
        ids = if (id in ids) ids - id else ids + id
    }

    fun clear() {
        ids = emptyList()
    }

    /** Drops anything no longer on the screen (an item that left the list), so the bar never counts what cannot be seen. */
    fun keepOnly(present: Set<String>) {
        if (ids.any { it !in present }) ids = ids.filter { it in present }
    }

    override fun toString() = "Selection($count)"

    companion object {
        val Saver = listSaver<Selection, String>({ it.ids }, { Selection(it) })
    }
}

/** The screen's selection for [session]: empty for a new profile, kept across rotation. */
@Composable
fun rememberSelection(session: Session?): Selection = rememberSaveable(session, saver = Selection.Saver) { Selection(emptyList()) }

/** Back clears the selection first (before leaving the screen), while there is one. */
@Composable
fun SelectionBackHandler(selection: Selection) {
    BackHandler(enabled = selection.active) { selection.clear() }
}

/**
 * What the bar can do. Each screen offers the ones that make sense there ([BulkAction.forBrowsing], [BulkAction.forDownloads]).
 * Milestone 11 adds `ADD_TO_PLAYLIST` and `ADD_TO_COLLECTION` here and one branch in `BulkViewModel.apply`.
 */
enum class BulkAction { MARK_WATCHED, MARK_UNWATCHED, FAVOURITE, UNFAVOURITE, DOWNLOAD, REMOVE_DOWNLOAD, DELETE_FROM_SERVER, MANAGE, MORE;

    companion object {
        /** Library grids, Home rows, search and a season's episodes. */
        val forBrowsing = listOf(MARK_WATCHED, MARK_UNWATCHED, FAVOURITE, UNFAVOURITE, DOWNLOAD, DELETE_FROM_SERVER, MANAGE, MORE)
        /** The Downloads tab: what makes sense for what is on the phone. */
        val forDownloads = listOf(MARK_WATCHED, MARK_UNWATCHED, FAVOURITE, UNFAVOURITE, REMOVE_DOWNLOAD, DELETE_FROM_SERVER, MANAGE)
    }
}

/** One picked item as the screen knows it: enough to act on it and to name it in a confirmation. */
data class BulkItem(val id: String, val title: String, val kind: ItemKind, val seriesId: String? = null) {
    override fun toString() = "BulkItem($kind)"
}
