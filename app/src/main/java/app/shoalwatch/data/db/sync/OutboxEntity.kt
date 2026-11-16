package app.shoalwatch.data.db.sync

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.shoalwatch.data.db.AccountEntity
import app.shoalwatch.data.library.ItemKind
import app.shoalwatch.data.sync.ChangeKind

/**
 * One change a profile made that the server has not yet confirmed (SPEC §3.2), sent only as that profile. [seq] is
 * `INTEGER PRIMARY KEY AUTOINCREMENT`, so it is never reused and is the send order. Deleted with the account; its
 * [SyncMarkEntity] rows go with it. Times are epoch milliseconds by the phone's wall clock.
 *
 * [flag] is set for PLAYED and FAVOURITE, [positionMs] for POSITION. [refreshKeys] are the list keys to recompute once it is
 * sent, joined by ',' (at most 2,000 characters). [sentOnce]: a request for this row may have reached the server.
 * [sentPlayCount]: for PLAY, the absolute PlayCount sent first, resent unchanged.
 */
@Entity(
    tableName = "sync_outbox",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["serverId", "userId"],
            childColumns = ["serverId", "userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("serverId", "userId", "seq")],
)
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val seq: Long = 0,
    val serverId: String,
    val userId: String,
    val itemId: String,
    val itemKind: ItemKind,
    val kind: ChangeKind,
    val flag: Boolean?,
    val positionMs: Long?,
    val occurredAt: Long,
    val refreshKeys: String,
    val fromOfflinePlay: Boolean,
    @ColumnInfo(defaultValue = "0") val sentOnce: Boolean = false,
    val sentPlayCount: Int? = null,
    @ColumnInfo(defaultValue = "0") val attempts: Int = 0,
    @ColumnInfo(defaultValue = "0") val serverErrors: Int = 0,
    @ColumnInfo(defaultValue = "0") val notBefore: Long = 0,
) {
    override fun toString() = "OutboxEntity(kind=$kind)"
}
