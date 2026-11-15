package app.shoalwatch.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Version 2 to 3: adds `track_choices` (the audio and subtitle choice per server, user and series, deleted with the
 * account). Nothing existing is touched. The statement is Room's own generated SQL for version 3, copied from
 * `app/schemas/.../3.json` (Room validates the migrated database against it when it opens); `MigrationTest` checks that a
 * migrated database and a fresh one have identical schemas. Plain `CREATE TABLE`: nothing newer than SQLite 3.22.
 */
object Migration2To3 : Migration(2, 3) {
    private const val CREATE_TRACK_CHOICES =
        "CREATE TABLE IF NOT EXISTS `track_choices` (`serverId` TEXT NOT NULL, `userId` TEXT NOT NULL, `seriesId` TEXT NOT NULL, `audioLanguage` TEXT, `subtitleLanguage` TEXT, `subtitlesOff` INTEGER NOT NULL, `subtitleForced` INTEGER NOT NULL, `subtitleHearingImpaired` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`serverId`, `userId`, `seriesId`), FOREIGN KEY(`serverId`, `userId`) REFERENCES `accounts`(`serverId`, `userId`) ON UPDATE NO ACTION ON DELETE CASCADE )"

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_TRACK_CHOICES)
    }
}
