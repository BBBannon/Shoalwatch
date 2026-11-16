package app.shoalwatch.data.db.downloads

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import app.shoalwatch.data.db.AccountEntity
import app.shoalwatch.data.downloads.AfterFinish

/**
 * One profile's own download settings, kept with the account: what happens when a downloaded item is finished (SPEC §3.1)
 * and how many next episodes to download automatically ([autoNextCount]: 0, 1, 2, 3 or 5). No row means ASK and 0.
 */
@Entity(
    tableName = "profile_download_settings",
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
data class ProfileDownloadSettingsEntity(
    val serverId: String,
    val userId: String,
    @ColumnInfo(defaultValue = "ASK") val afterFinish: AfterFinish,
    @ColumnInfo(defaultValue = "0") val autoNextCount: Int,
) {
    override fun toString() = "ProfileDownloadSettingsEntity(afterFinish=$afterFinish, autoNextCount=$autoNextCount)"
}
