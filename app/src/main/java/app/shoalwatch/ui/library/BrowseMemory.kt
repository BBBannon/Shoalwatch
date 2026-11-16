package app.shoalwatch.ui.library

import app.shoalwatch.data.library.ItemFilter
import app.shoalwatch.data.library.Sort
import app.shoalwatch.data.library.SortBy
import app.shoalwatch.data.servers.Session
import kotlinx.coroutines.flow.MutableStateFlow

/** Name, A to Z: how every library starts. */
val DEFAULT_SORT = Sort(SortBy.NAME, descending = false)

/** The sort and filter chosen for one library. */
data class SortFilter(val sort: Sort = DEFAULT_SORT, val filter: ItemFilter = ItemFilter())

/**
 * What the user chose to sort and filter each library by, kept for as long as the app is open (the process), per signed-in
 * account and library, so leaving a library and coming back finds it as it was left. Nothing is saved to disk. One instance
 * lives in `AppGraph`; tests make their own.
 */
class BrowseMemory {
    private val choices = HashMap<Triple<String, String, String>, MutableStateFlow<SortFilter>>()

    /** The live choice for [parentId] as [session]. Setting its value changes what that library shows. */
    @Synchronized
    fun choice(session: Session, parentId: String): MutableStateFlow<SortFilter> =
        choices.getOrPut(Triple(session.serverId, session.userId, parentId)) { MutableStateFlow(SortFilter()) }
}
