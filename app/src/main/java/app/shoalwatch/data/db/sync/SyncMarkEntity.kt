package app.shoalwatch.data.db.sync

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Ties an outbox row to the cached rows it changed. [leftList] "" is a plain mark ("this row's `pendingSyncAt` is mine"); any
 * other value is a list key the change took the item out of (for example "resume"). Deleted with its outbox row.
 */
@Entity(
    tableName = "sync_marks",
    primaryKeys = ["seq", "itemId", "leftList"],
    foreignKeys = [
        ForeignKey(entity = OutboxEntity::class, parentColumns = ["seq"], childColumns = ["seq"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("serverId", "userId", "itemId")],
)
data class SyncMarkEntity(
    val seq: Long,
    val serverId: String,
    val userId: String,
    val itemId: String,
    @ColumnInfo(defaultValue = "") val leftList: String,
) {
    override fun toString() = "SyncMarkEntity(<redacted>)"
}
