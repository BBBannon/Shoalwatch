package app.shoalwatch.data.library

import app.shoalwatch.data.db.library.ListEntryEntity
import app.shoalwatch.data.servers.JellyfinId

/**
 * The ONLY place a list-key string is built. A key is an opaque string stored in `list_entries.listKey` and
 * `list_state.listKey` (at most 200 characters). Every id is a normalised 32-digit `JellyfinId`, so `:` is a safe
 * separator and two different inputs can never build the same key.
 *
 * Two keys hold state only (no entries): [views] and [item], whose `list_state.refreshedAt` is how current they are.
 */
internal object ListKey {
    const val RESUME = "resume"
    const val NEXT_UP = "nextUp"
    const val VIEWS = "views"

    fun latest(viewId: String) = "latest:${id(viewId)}"

    fun children(parentId: String, shape: ListShape, sort: Sort, filter: ItemFilter) =
        "children:${id(parentId)}:${shape.key}:${sort.key}:${filter.key}".also(::checkLength)

    fun seasons(seriesId: String) = "seasons:${id(seriesId)}"

    /** [seasonId] null means the whole series. */
    fun episodes(seriesId: String, seasonId: String?) = "episodes:${id(seriesId)}:${seasonId?.let(::id) ?: "all"}"

    fun item(itemId: String) = "item:${id(itemId)}"

    /** Ids come from the app's own models, so a bad one is a programming error, not something to recover from. */
    private fun id(raw: String): String = requireNotNull(JellyfinId.parseOrNull(raw)) { "A list key is built from normalised ids." }.value

    private fun checkLength(key: String) = check(key.length <= ListEntryEntity.MAX_LIST_KEY) { "A list key is at most 200 characters." }
}
