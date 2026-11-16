package app.shoalwatch.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/** Raw rows for the eight download tables (schema v4), written with plain SQL so the tests do not depend on the DAOs. */
internal object DownloadRows {
    fun download(db: SupportSQLiteDatabase, serverId: String, itemId: String, state: String = "QUEUED", seriesId: String? = null, position: Long = 1) =
        db.execSQL(
            "INSERT INTO downloads (serverId, itemId, kind, seriesId, seasonId, quality, state, failure, storage, volumeId, mediaSourceId, container, " +
                "totalBytes, doneBytes, segmentsTotal, segmentsDone, validator, restarts, attempts, runtimeMs, audioIndex, sha256, queuePosition, " +
                "requestedAt, completedAt, verifiedAt) VALUES (?, ?, 'EPISODE', ?, NULL, 'ORIGINAL', ?, NULL, 'INTERNAL', NULL, 'abc123', 'mp4', " +
                "1000, 10, NULL, 0, 'a1b2c3', 0, 1, 60000, 1, NULL, ?, 5, NULL, NULL)",
            arrayOf(serverId, itemId, seriesId, state, position),
        )

    fun owner(db: SupportSQLiteDatabase, serverId: String, userId: String, itemId: String) =
        db.execSQL("INSERT INTO download_owners (serverId, userId, itemId, addedAt, fetched) VALUES (?, ?, ?, 6, 1)", arrayOf(serverId, userId, itemId))

    fun track(db: SupportSQLiteDatabase, serverId: String, itemId: String) =
        db.execSQL(
            "INSERT INTO download_tracks (serverId, itemId, type, streamIndex, label, language, codec, kind, fileFormat, forced, hearingImpaired, isDefault, " +
                "external, channels, fileSaved) VALUES (?, ?, 'SUBTITLE', 2, 'English', 'eng', 'subrip', 'TEXT', 'srt', 0, 0, 0, 1, NULL, 1)",
            arrayOf(serverId, itemId),
        )

    fun segment(db: SupportSQLiteDatabase, serverId: String, itemId: String) =
        db.execSQL("INSERT INTO download_segments (serverId, itemId, kind, startMs, endMs) VALUES (?, ?, 'INTRO', 1000, 3000)", arrayOf(serverId, itemId))

    fun artwork(db: SupportSQLiteDatabase, serverId: String, itemId: String) =
        db.execSQL("INSERT INTO pinned_artwork (serverId, itemId, slot, tag, bytes, savedAt) VALUES (?, ?, 'PRIMARY', 'tag1', 2048, 7)", arrayOf(serverId, itemId))

    fun settings(db: SupportSQLiteDatabase, serverId: String, share: Boolean = true) =
        db.execSQL("INSERT INTO download_settings (serverId, shareBetweenProfiles) VALUES (?, ?)", arrayOf(serverId, if (share) 1 else 0))

    fun offlinePlay(db: SupportSQLiteDatabase, serverId: String, userId: String, itemId: String) =
        db.execSQL(
            "INSERT INTO offline_plays (serverId, userId, itemId, positionMs, played, playedAt, lastPlayedAt, updatedAt) VALUES (?, ?, ?, 1500, 0, NULL, 8, 9)",
            arrayOf(serverId, userId, itemId),
        )

    fun permission(db: SupportSQLiteDatabase, serverId: String, userId: String, itemId: String) =
        db.execSQL("INSERT INTO download_permissions (serverId, userId, itemId, confirmedAt) VALUES (?, ?, ?, 10)", arrayOf(serverId, userId, itemId))

    /** One of each for (serverId, userId, itemId): a download with an owner, a track and a segment, artwork, settings and an offline play. */
    fun fullSet(db: SupportSQLiteDatabase, serverId: String, userId: String, itemId: String) {
        download(db, serverId, itemId)
        owner(db, serverId, userId, itemId)
        track(db, serverId, itemId)
        segment(db, serverId, itemId)
        artwork(db, serverId, itemId)
        offlinePlay(db, serverId, userId, itemId)
        permission(db, serverId, userId, itemId)
    }

    val TABLES = listOf("downloads", "download_owners", "download_tracks", "download_segments", "pinned_artwork", "download_settings", "offline_plays", "download_permissions")

    fun count(db: SupportSQLiteDatabase, table: String, where: String = "1=1"): Int =
        db.query("SELECT COUNT(*) FROM $table WHERE $where").use { it.moveToFirst(); it.getInt(0) }
}
