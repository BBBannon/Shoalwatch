package app.shoalwatch.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 4 to 5 (Milestone 5): the outbox and housekeeping tables. In this order:
 * 1. the seven new tables (`sync_outbox`, `sync_marks`, `sync_state`, `rejected_sign_ins`, `released_episodes`,
 *    `profile_download_settings`, `auto_next_requests`) and their three indices;
 * 2. `download_settings` rebuilt for its new `deleteShared` column: renamed, created afresh, copied with 'ANYONE', the old one
 *    dropped. (`ALTER TABLE ADD COLUMN` would put the column after the table's constraints, so `sqlite_master` would differ
 *    from a fresh database. Nothing references `download_settings`, so the rename touches no other table on any SQLite.)
 * 3. Milestone 4's offline plays become outbox rows, so nothing watched under v4 is lost: a PLAY at `playedAt` for each
 *    finished play, then a POSITION at `updatedAt` for each unfinished one or one watched again part way since, each with
 *    one plain mark. `offline_plays` itself is left as it was.
 *
 * Every `CREATE` is Room's own generated SQL for version 5, copied verbatim from `app/schemas/.../5.json` (Room validates the
 * migrated database against it when it opens); `MigrationTest` checks a migrated database and a fresh one have identical
 * schemas, from version 4 and from version 1. Plain `CREATE`, `ALTER TABLE ... RENAME TO`, `INSERT ... SELECT` and
 * `COALESCE`: nothing newer than SQLite 3.22.
 */
object Migration4To5 : Migration(4, 5) {
    private val STATEMENTS = listOf(
        "CREATE TABLE IF NOT EXISTS `sync_outbox` (`seq` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `itemKind` TEXT NOT NULL, `kind` TEXT NOT NULL, `flag` INTEGER, `positionMs` INTEGER, `occurredAt` INTEGER NOT NULL, `refreshKeys` TEXT NOT NULL, `fromOfflinePlay` INTEGER NOT NULL, `sentOnce` INTEGER NOT NULL DEFAULT 0, `sentPlayCount` INTEGER, `attempts` INTEGER NOT NULL DEFAULT 0, `serverErrors` INTEGER NOT NULL DEFAULT 0, `notBefore` INTEGER NOT NULL DEFAULT 0, FOREIGN KEY(`serverId`, `userId`) REFERENCES `accounts`(`serverId`, `userId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `sync_marks` (`seq` INTEGER NOT NULL, `serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `leftList` TEXT NOT NULL DEFAULT '', PRIMARY KEY(`seq`, `itemId`, `leftList`), FOREIGN KEY(`seq`) REFERENCES `sync_outbox`(`seq`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `sync_state` (`serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, `lastSyncedAt` INTEGER NOT NULL, PRIMARY KEY(`serverId`, `userId`), FOREIGN KEY(`serverId`, `userId`) REFERENCES `accounts`(`serverId`, `userId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `rejected_sign_ins` (`serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, `rejectedAt` INTEGER NOT NULL, PRIMARY KEY(`serverId`, `userId`), FOREIGN KEY(`serverId`, `userId`) REFERENCES `accounts`(`serverId`, `userId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `released_episodes` (`serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, `itemId` TEXT NOT NULL, `seriesId` TEXT NOT NULL, `seasonId` TEXT, `releasedAt` INTEGER NOT NULL, PRIMARY KEY(`serverId`, `userId`, `itemId`), FOREIGN KEY(`serverId`, `userId`) REFERENCES `accounts`(`serverId`, `userId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `profile_download_settings` (`serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, `afterFinish` TEXT NOT NULL DEFAULT 'ASK', `autoNextCount` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`serverId`, `userId`), FOREIGN KEY(`serverId`, `userId`) REFERENCES `accounts`(`serverId`, `userId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `auto_next_requests` (`serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, `seriesId` TEXT NOT NULL, `anchorItemId` TEXT NOT NULL, `quality` TEXT NOT NULL, `requestedAt` INTEGER NOT NULL, `tries` INTEGER NOT NULL DEFAULT 0, `blockedAt` INTEGER, PRIMARY KEY(`serverId`, `userId`, `seriesId`), FOREIGN KEY(`serverId`, `userId`) REFERENCES `accounts`(`serverId`, `userId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE INDEX IF NOT EXISTS `index_sync_outbox_serverId_userId_seq` ON `sync_outbox` (`serverId`, `userId`, `seq`)",
        "CREATE INDEX IF NOT EXISTS `index_sync_marks_serverId_userId_itemId` ON `sync_marks` (`serverId`, `userId`, `itemId`)",
        "CREATE INDEX IF NOT EXISTS `index_released_episodes_serverId_seriesId` ON `released_episodes` (`serverId`, `seriesId`)",
        "ALTER TABLE `download_settings` RENAME TO `download_settings_v4`",
        "CREATE TABLE IF NOT EXISTS `download_settings` (`serverId` TEXT NOT NULL, `shareBetweenProfiles` INTEGER NOT NULL DEFAULT 0, `deleteShared` TEXT NOT NULL DEFAULT 'ANYONE', PRIMARY KEY(`serverId`), FOREIGN KEY(`serverId`) REFERENCES `servers`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "INSERT INTO `download_settings` (`serverId`, `shareBetweenProfiles`, `deleteShared`) SELECT `serverId`, `shareBetweenProfiles`, 'ANYONE' FROM `download_settings_v4`",
        "DROP TABLE `download_settings_v4`",
        "INSERT INTO sync_outbox (serverId, userId, itemId, itemKind, kind, flag, positionMs, occurredAt, refreshKeys, fromOfflinePlay, sentOnce, sentPlayCount, attempts, serverErrors, notBefore) " +
            "SELECT p.serverId, p.userId, p.itemId, COALESCE((SELECT i.kind FROM items i WHERE i.serverId = p.serverId AND i.itemId = p.itemId), 'OTHER'), " +
            "'PLAY', NULL, NULL, p.playedAt, 'resume,nextUp', 1, 0, NULL, 0, 0, 0 FROM offline_plays p " +
            "WHERE p.played = 1 AND p.playedAt IS NOT NULL ORDER BY p.playedAt",
        "INSERT INTO sync_outbox (serverId, userId, itemId, itemKind, kind, flag, positionMs, occurredAt, refreshKeys, fromOfflinePlay, sentOnce, sentPlayCount, attempts, serverErrors, notBefore) " +
            "SELECT p.serverId, p.userId, p.itemId, COALESCE((SELECT i.kind FROM items i WHERE i.serverId = p.serverId AND i.itemId = p.itemId), 'OTHER'), " +
            "'POSITION', NULL, p.positionMs, p.updatedAt, 'resume,nextUp', 1, 0, NULL, 0, 0, 0 FROM offline_plays p " +
            "WHERE p.played = 0 OR (p.positionMs > 0 AND p.updatedAt > p.playedAt) ORDER BY p.updatedAt",
        "INSERT INTO sync_marks (seq, serverId, userId, itemId, leftList) SELECT seq, serverId, userId, itemId, '' FROM sync_outbox",
    )

    override fun migrate(db: SupportSQLiteDatabase) {
        STATEMENTS.forEach(db::execSQL)
    }
}
