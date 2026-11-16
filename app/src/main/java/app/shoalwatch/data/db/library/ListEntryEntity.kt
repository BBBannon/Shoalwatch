package app.shoalwatch.data.db.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import app.shoalwatch.data.db.AccountEntity

/**
 * Ordered membership of one list for one user: "this item is at [position] in list [listKey]". Positions are contiguous
 * from 0. No item data lives here (reading a list joins to `items` and `user_data`), and there is deliberately no foreign
 * key to `items`: an entry whose item row is missing is simply not returned, and orphans are cleaned by maintenance.
 * [listKey] is an opaque string of at most [MAX_LIST_KEY] characters chosen by the caller. Deleted with its account.
 */
@Entity(
    tableName = "list_entries",
    primaryKeys = ["serverId", "userId", "listKey", "position"],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["serverId", "userId"],
            childColumns = ["serverId", "userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("serverId", "itemId")],
)
data class ListEntryEntity(
    val serverId: String,
    val userId: String,
    val listKey: String,
    val position: Int,
    val itemId: String,
) {
    companion object {
        const val MAX_LIST_KEY = 200
    }
}
