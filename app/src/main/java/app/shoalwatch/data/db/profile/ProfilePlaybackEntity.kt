package app.shoalwatch.data.db.profile

import androidx.room.Entity
import androidx.room.ForeignKey
import app.shoalwatch.data.db.AccountEntity

/** One profile's own playback settings (Milestone 7). No row means the defaults (autoplay on). Deleted with its account. */
@Entity(
    tableName = "profile_playback",
    primaryKeys = ["serverId", "userId"],
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["serverId", "userId"],
            childColumns = ["serverId", "userId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
class ProfilePlaybackEntity(val serverId: String, val userId: String, val autoplayNext: Boolean) {
    override fun toString() = "ProfilePlaybackEntity(<redacted>)"
}
