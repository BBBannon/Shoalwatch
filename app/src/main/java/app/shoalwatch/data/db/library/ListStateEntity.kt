package app.shoalwatch.data.db.library

import androidx.room.Entity
import androidx.room.ForeignKey
import app.shoalwatch.data.db.AccountEntity

/**
 * What is known about one list: how many items the server said it has ([total], null when it gave no usable count), how many
 * are stored ([loadedCount]) and when it was last written (epoch ms), which is what eviction goes by. For a list read in
 * pages, [nextStartIndex] is the server index the next page starts at (it is NOT [loadedCount]: the gateway skips malformed
 * elements and removes repeats, so fewer rows are stored than the server sent), [pagesLoaded] counts the pages fetched, and
 * [endReached] says the server has nothing more. A list read in one go has `endReached = true`. Deleted with its account.
 */
@Entity(
    tableName = "list_state",
    primaryKeys = ["serverId", "userId", "listKey"],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["serverId", "userId"],
            childColumns = ["serverId", "userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ListStateEntity(
    val serverId: String,
    val userId: String,
    val listKey: String,
    val total: Int?,
    val loadedCount: Int,
    val nextStartIndex: Int,
    val pagesLoaded: Int,
    val endReached: Boolean,
    val refreshedAt: Long,
)
