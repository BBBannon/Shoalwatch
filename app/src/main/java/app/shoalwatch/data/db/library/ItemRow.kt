package app.shoalwatch.data.db.library

import androidx.room.Embedded

/**
 * An item joined to one user's data for it: what every browsing query returns. [userData] is null when that user has
 * nothing stored for the item. Use [toModel] to get the app's own types.
 */
class ItemRow(
    @Embedded val item: ItemEntity,
    @Embedded(prefix = "ud_") val userData: UserDataEntity?,
)

/** The columns of an [ItemRow] query whose tables are aliased `i` (items) and `u` (user_data, LEFT JOINed). */
internal const val ROW_COLUMNS =
    "i.*, u.serverId AS ud_serverId, u.userId AS ud_userId, u.itemId AS ud_itemId, u.played AS ud_played, " +
        "u.positionMs AS ud_positionMs, u.playedPercentage AS ud_playedPercentage, u.playCount AS ud_playCount, " +
        "u.favourite AS ud_favourite, u.lastPlayed AS ud_lastPlayed, u.unplayedItemCount AS ud_unplayedItemCount, " +
        "u.updatedAt AS ud_updatedAt, u.pendingSyncAt AS ud_pendingSyncAt"
