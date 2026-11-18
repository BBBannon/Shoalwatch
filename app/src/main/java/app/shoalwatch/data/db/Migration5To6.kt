package app.shoalwatch.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 5 to 6 (Milestone 7): the two new tables `profile_locks` and `profile_playback`, and nothing else. No existing table
 * is altered or rebuilt. Each `CREATE` is Room's own generated SQL for version 6, copied verbatim from
 * `app/schemas/.../6.json`; `MigrationTest` checks a migrated database and a fresh one have identical schemas. Plain `CREATE`:
 * nothing newer than SQLite 3.22.
 */
object Migration5To6 : Migration(5, 6) {
    private val STATEMENTS = listOf(
        "CREATE TABLE IF NOT EXISTS `profile_locks` (`serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, `verifier` BLOB NOT NULL, `failures` INTEGER NOT NULL, PRIMARY KEY(`serverId`, `userId`), FOREIGN KEY(`serverId`, `userId`) REFERENCES `accounts`(`serverId`, `userId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        "CREATE TABLE IF NOT EXISTS `profile_playback` (`serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, `autoplayNext` INTEGER NOT NULL, PRIMARY KEY(`serverId`, `userId`), FOREIGN KEY(`serverId`, `userId`) REFERENCES `accounts`(`serverId`, `userId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
    )

    override fun migrate(db: SupportSQLiteDatabase) {
        STATEMENTS.forEach(db::execSQL)
    }
}
