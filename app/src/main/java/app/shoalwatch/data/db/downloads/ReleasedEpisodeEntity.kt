package app.shoalwatch.data.db.downloads

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import app.shoalwatch.data.db.AccountEntity

/**
 * "This profile watched and deleted this episode" (watch-and-delete, SPEC §3.1): keeps the episode, its season and its
 * series pinned so the offline series still lists it. Deleted with the account. [releasedAt] is epoch milliseconds.
 */
@Entity(
    tableName = "released_episodes",
    primaryKeys = ["serverId", "userId", "itemId"],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["serverId", "userId"],
            childColumns = ["serverId", "userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("serverId", "seriesId")],
)
data class ReleasedEpisodeEntity(
    val serverId: String,
    val userId: String,
    val itemId: String,
    val seriesId: String,
    val seasonId: String?,
    val releasedAt: Long,
) {
    override fun toString() = "ReleasedEpisodeEntity(<redacted>)"
}
