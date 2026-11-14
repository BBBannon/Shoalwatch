package app.shoalwatch.data.db.library

import androidx.room.Entity
import androidx.room.ForeignKey
import app.shoalwatch.data.db.AccountEntity

/**
 * What is known about one list: how many items the server said it has ([total]), how many are stored ([loadedCount]) and
 * when it was last written (epoch ms), which is what eviction goes by. Deleted with its account.
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
    val total: Int,
    val loadedCount: Int,
    val refreshedAt: Long,
)
