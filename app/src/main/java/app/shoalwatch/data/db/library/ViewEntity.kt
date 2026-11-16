package app.shoalwatch.data.db.library

import androidx.room.Entity
import androidx.room.ForeignKey
import app.shoalwatch.data.db.AccountEntity

/** One of a user's libraries, in the order the server listed them ([position]). [kind] is a `LibraryKind` name. Deleted with its account. */
@Entity(
    tableName = "views",
    primaryKeys = ["serverId", "userId", "viewId"],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["serverId", "userId"],
            childColumns = ["serverId", "userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ViewEntity(
    val serverId: String,
    val userId: String,
    val viewId: String,
    val name: String,
    val kind: String,
    val position: Int,
)
