package app.shoalwatch.data.db.library

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import app.shoalwatch.data.db.AccountEntity

/**
 * "The server has shown this user this item" (SAFEGUARDING). Item rows are shared per server, but a profile (a child, say)
 * may be allowed fewer items than another on the same server, so the app only ever shows a user an item the server returned
 * to THAT user: it is recorded here, in the same transaction as every server-sourced item write for the session, and every
 * read of a shared row for a user joins to it. An item that is not here for the user is treated exactly as absent.
 *
 * Only what was returned is recorded (the parents of a listed episode are not inferred). Rows go when their item is evicted
 * and stay while the item is kept (pinned downloads rely on this record as the "allowed to see" check). Deleted with its
 * account; signing in again keeps the account, so it keeps the record. [seenAt] is epoch ms of the latest return.
 */
@Entity(
    tableName = "visible_items",
    primaryKeys = ["serverId", "userId", "itemId"],
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
data class VisibleItemEntity(
    val serverId: String,
    val userId: String,
    val itemId: String,
    val seenAt: Long,
)
