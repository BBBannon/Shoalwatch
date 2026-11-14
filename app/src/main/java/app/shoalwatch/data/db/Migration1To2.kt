package app.shoalwatch.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 1 to 2: adds the library cache (items, user data, views, list entries, list state) and its indices. Nothing
 * existing is touched, so servers, accounts and their sealed tokens come through byte for byte.
 *
 * The statements are Room's own generated SQL for version 2, copied from `app/schemas/.../2.json`: Room compares the
 * migrated database with that schema when it opens, and any difference crashes the app. `MigrationTest` checks that a
 * migrated database and a fresh one have identical schemas, so a change to an entity without a matching change here fails a test.
 */
object Migration1To2 : Migration(1, 2) {
    private val statements = listOf(
        "CREATE TABLE IF NOT EXISTS `items` (`serverId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `kind` TEXT NOT NULL, `name` TEXT NOT NULL, `nameKey` TEXT NOT NULL, `sortName` TEXT, `parentId` TEXT, `seriesId` TEXT, `seriesName` TEXT, `seasonId` TEXT, `indexNumber` INTEGER, `parentIndexNumber` INTEGER, `overview` TEXT, `runtimeMs` INTEGER, `year` INTEGER, `premiereDate` INTEGER, `communityRating` REAL, `officialRating` TEXT, `childCount` INTEGER, `primaryTag` TEXT, `backdropTag` TEXT, `thumbTag` TEXT, `seriesPrimaryTag` TEXT, `fetchedAt` INTEGER NOT NULL, `pinned` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`serverId`, `itemId`), FOREIGN KEY(`serverId`) REFERENCES `servers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_items_serverId_parentId` ON `items` (`serverId`, `parentId`)",
        "CREATE INDEX IF NOT EXISTS `index_items_serverId_seriesId_seasonId_parentIndexNumber_indexNumber` ON `items` (`serverId`, `seriesId`, `seasonId`, `parentIndexNumber`, `indexNumber`)",
        "CREATE TABLE IF NOT EXISTS `user_data` (`serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `played` INTEGER NOT NULL, `positionMs` INTEGER NOT NULL, `playedPercentage` REAL, `playCount` INTEGER NOT NULL, `favourite` INTEGER NOT NULL, `lastPlayed` INTEGER, `unplayedItemCount` INTEGER, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`serverId`, `userId`, `itemId`), FOREIGN KEY(`serverId`, `userId`) REFERENCES `accounts`(`serverId`, `userId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_user_data_serverId_itemId` ON `user_data` (`serverId`, `itemId`)",
        "CREATE TABLE IF NOT EXISTS `views` (`serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, `viewId` TEXT NOT NULL, `name` TEXT NOT NULL, `kind` TEXT NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`serverId`, `userId`, `viewId`), FOREIGN KEY(`serverId`, `userId`) REFERENCES `accounts`(`serverId`, `userId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `list_entries` (`serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, `listKey` TEXT NOT NULL, `position` INTEGER NOT NULL, `itemId` TEXT NOT NULL, PRIMARY KEY(`serverId`, `userId`, `listKey`, `position`), FOREIGN KEY(`serverId`, `userId`) REFERENCES `accounts`(`serverId`, `userId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_list_entries_serverId_itemId` ON `list_entries` (`serverId`, `itemId`)",
        "CREATE TABLE IF NOT EXISTS `list_state` (`serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, `listKey` TEXT NOT NULL, `total` INTEGER, `loadedCount` INTEGER NOT NULL, `nextStartIndex` INTEGER NOT NULL, `pagesLoaded` INTEGER NOT NULL, `endReached` INTEGER NOT NULL, `refreshedAt` INTEGER NOT NULL, PRIMARY KEY(`serverId`, `userId`, `listKey`), FOREIGN KEY(`serverId`, `userId`) REFERENCES `accounts`(`serverId`, `userId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    )

    override fun migrate(db: SupportSQLiteDatabase) {
        statements.forEach(db::execSQL)
    }
}
