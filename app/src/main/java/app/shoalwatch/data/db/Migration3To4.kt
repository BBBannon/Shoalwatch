package app.shoalwatch.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 3 to 4: the seven download tables (`downloads`, `download_owners`, `download_tracks`, `download_segments`,
 * `pinned_artwork`, `download_settings`, `offline_plays`) and their three indices. Nothing existing is touched. The
 * statements are Room's own generated SQL for version 4, copied verbatim from `app/schemas/.../4.json` (Room validates the
 * migrated database against it when it opens); `MigrationTest` checks that a migrated database and a fresh one have
 * identical schemas, from version 3 and from version 1. Plain `CREATE TABLE` and `CREATE INDEX`: nothing newer than SQLite 3.22.
 */
object Migration3To4 : Migration(3, 4) {
    private val STATEMENTS = listOf(
        "CREATE TABLE IF NOT EXISTS `downloads` (`serverId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `kind` TEXT NOT NULL, `seriesId` TEXT, `seasonId` TEXT, `quality` TEXT NOT NULL, `state` TEXT NOT NULL, `failure` TEXT, `storage` TEXT NOT NULL, `volumeId` TEXT, `mediaSourceId` TEXT, `container` TEXT, `totalBytes` INTEGER, `doneBytes` INTEGER NOT NULL, `segmentsTotal` INTEGER, `segmentsDone` INTEGER NOT NULL, `validator` TEXT, `restarts` INTEGER NOT NULL, `attempts` INTEGER NOT NULL, `runtimeMs` INTEGER, `audioIndex` INTEGER, `sha256` TEXT, `queuePosition` INTEGER NOT NULL, `requestedAt` INTEGER NOT NULL, `completedAt` INTEGER, `verifiedAt` INTEGER, PRIMARY KEY(`serverId`, `itemId`), FOREIGN KEY(`serverId`) REFERENCES `servers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `download_owners` (`serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `addedAt` INTEGER NOT NULL, PRIMARY KEY(`serverId`, `userId`, `itemId`), FOREIGN KEY(`serverId`, `userId`) REFERENCES `accounts`(`serverId`, `userId`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`serverId`, `itemId`) REFERENCES `downloads`(`serverId`, `itemId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `download_tracks` (`serverId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `type` TEXT NOT NULL, `streamIndex` INTEGER NOT NULL, `label` TEXT NOT NULL, `language` TEXT, `codec` TEXT, `kind` TEXT, `fileFormat` TEXT, `forced` INTEGER NOT NULL, `hearingImpaired` INTEGER NOT NULL, `isDefault` INTEGER NOT NULL, `external` INTEGER NOT NULL, `channels` INTEGER, `fileSaved` INTEGER NOT NULL, PRIMARY KEY(`serverId`, `itemId`, `type`, `streamIndex`), FOREIGN KEY(`serverId`, `itemId`) REFERENCES `downloads`(`serverId`, `itemId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `download_segments` (`serverId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `kind` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, PRIMARY KEY(`serverId`, `itemId`, `kind`, `startMs`), FOREIGN KEY(`serverId`, `itemId`) REFERENCES `downloads`(`serverId`, `itemId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `pinned_artwork` (`serverId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `slot` TEXT NOT NULL, `tag` TEXT NOT NULL, `bytes` INTEGER NOT NULL, `savedAt` INTEGER NOT NULL, PRIMARY KEY(`serverId`, `itemId`, `slot`), FOREIGN KEY(`serverId`) REFERENCES `servers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `download_settings` (`serverId` TEXT NOT NULL, `shareBetweenProfiles` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`serverId`), FOREIGN KEY(`serverId`) REFERENCES `servers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `offline_plays` (`serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `positionMs` INTEGER NOT NULL, `played` INTEGER NOT NULL, `playedAt` INTEGER, `lastPlayedAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`serverId`, `userId`, `itemId`), FOREIGN KEY(`serverId`, `userId`) REFERENCES `accounts`(`serverId`, `userId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_downloads_serverId_seriesId` ON `downloads` (`serverId`, `seriesId`)",
        "CREATE INDEX IF NOT EXISTS `index_downloads_state_queuePosition` ON `downloads` (`state`, `queuePosition`)",
        "CREATE INDEX IF NOT EXISTS `index_download_owners_serverId_itemId` ON `download_owners` (`serverId`, `itemId`)",
    )

    override fun migrate(db: SupportSQLiteDatabase) {
        STATEMENTS.forEach(db::execSQL)
    }
}
