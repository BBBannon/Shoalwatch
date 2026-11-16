package app.shoalwatch.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/** Raw rows for the seven tables schema v5 adds (Milestone 5), written with plain SQL so the tests do not depend on the DAOs. */
internal object SyncRows {
    /** One outbox row; returns its `seq` (the highest: AUTOINCREMENT. `last_insert_rowid()` would be per connection, and Room pools them). */
    fun outbox(db: SupportSQLiteDatabase, serverId: String, userId: String, itemId: String, kind: String = "PLAYED", occurredAt: Long = 20): Long {
        db.execSQL(
            "INSERT INTO sync_outbox (serverId, userId, itemId, itemKind, kind, flag, positionMs, occurredAt, refreshKeys, fromOfflinePlay, " +
                "sentOnce, sentPlayCount, attempts, serverErrors, notBefore) VALUES (?, ?, ?, 'EPISODE', ?, 1, NULL, ?, 'resume', 0, 0, NULL, 0, 0, 0)",
            arrayOf(serverId, userId, itemId, kind, occurredAt),
        )
        return db.query("SELECT MAX(seq) FROM sync_outbox").use { it.moveToFirst(); it.getLong(0) }
    }

    fun mark(db: SupportSQLiteDatabase, seq: Long, serverId: String, userId: String, itemId: String, leftList: String = "") =
        db.execSQL("INSERT INTO sync_marks (seq, serverId, userId, itemId, leftList) VALUES (?, ?, ?, ?, ?)", arrayOf(seq, serverId, userId, itemId, leftList))

    fun state(db: SupportSQLiteDatabase, serverId: String, userId: String) =
        db.execSQL("INSERT INTO sync_state (serverId, userId, lastSyncedAt) VALUES (?, ?, 21)", arrayOf(serverId, userId))

    fun rejected(db: SupportSQLiteDatabase, serverId: String, userId: String) =
        db.execSQL("INSERT INTO rejected_sign_ins (serverId, userId, rejectedAt) VALUES (?, ?, 22)", arrayOf(serverId, userId))

    fun released(db: SupportSQLiteDatabase, serverId: String, userId: String, itemId: String, seriesId: String) =
        db.execSQL(
            "INSERT INTO released_episodes (serverId, userId, itemId, seriesId, seasonId, releasedAt) VALUES (?, ?, ?, ?, NULL, 23)",
            arrayOf(serverId, userId, itemId, seriesId),
        )

    fun profileSettings(db: SupportSQLiteDatabase, serverId: String, userId: String) =
        db.execSQL("INSERT INTO profile_download_settings (serverId, userId, afterFinish, autoNextCount) VALUES (?, ?, 'ALWAYS', 2)", arrayOf(serverId, userId))

    fun autoNext(db: SupportSQLiteDatabase, serverId: String, userId: String, seriesId: String, anchorItemId: String) =
        db.execSQL(
            "INSERT INTO auto_next_requests (serverId, userId, seriesId, anchorItemId, quality, requestedAt, tries, blockedAt) VALUES (?, ?, ?, ?, 'HIGH', 24, 1, NULL)",
            arrayOf(serverId, userId, seriesId, anchorItemId),
        )

    /** One of each for (serverId, userId): an outbox row with a plain mark and a list mark, and a row in every other new table. */
    fun fullSet(db: SupportSQLiteDatabase, serverId: String, userId: String, itemId: String, seriesId: String) {
        val seq = outbox(db, serverId, userId, itemId)
        mark(db, seq, serverId, userId, itemId)
        mark(db, seq, serverId, userId, itemId, "resume")
        state(db, serverId, userId)
        rejected(db, serverId, userId)
        released(db, serverId, userId, itemId, seriesId)
        profileSettings(db, serverId, userId)
        autoNext(db, serverId, userId, seriesId, itemId)
    }

    val TABLES = listOf("sync_outbox", "sync_marks", "sync_state", "rejected_sign_ins", "released_episodes", "profile_download_settings", "auto_next_requests")
}
