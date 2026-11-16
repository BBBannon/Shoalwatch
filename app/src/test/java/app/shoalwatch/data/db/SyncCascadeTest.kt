package app.shoalwatch.data.db

import app.shoalwatch.S1
import app.shoalwatch.S2
import app.shoalwatch.U1
import app.shoalwatch.U2
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seven tables schema v5 adds, through the real [app.shoalwatch.data.servers.ServerStore]: an account takes its outbox (and
 * the outbox takes its marks), its sync state, its rejected sign-in, its released episodes, its per-profile download settings
 * and its auto-download requests, and nothing of another account; a server takes all of them for every account on it, and
 * its `download_settings`.
 */
class SyncCascadeTest : LibraryTestBase() {
    private val raw get() = db.openHelper.writableDatabase
    private val a = id("a")
    private val show = id("show")
    private val everyone = listOf(S1 to U1, S1 to U2, S2 to U1)

    private fun fill() {
        for ((server, user) in everyone) SyncRows.fullSet(raw, server, user, a, show)
        DownloadRows.settings(raw, S1)
        DownloadRows.settings(raw, S2)
    }

    private fun n(table: String, where: String = "1=1") = DownloadRows.count(raw, table, where)

    private fun of(server: String, user: String) = SyncRows.TABLES.associateWith { n(it, "serverId = '$server' AND userId = '$user'") }

    @Test fun everyNewTableHoldsARowPerAccount() {
        fill()
        assertEquals(mapOf("sync_outbox" to 1, "sync_marks" to 2, "sync_state" to 1, "rejected_sign_ins" to 1, "released_episodes" to 1,
            "profile_download_settings" to 1, "auto_next_requests" to 1), of(S1, U1))
    }

    @Test fun removingAnAccountDeletesEveryRowOfItsOwnAndNothingOfAnotherAccount() = runBlocking {
        fill()
        val others = everyone.drop(1).associateWith { (server, user) -> of(server, user) }
        servers.removeAccount(S1, U1)
        assertEquals(SyncRows.TABLES.associateWith { 0 }, of(S1, U1))
        assertEquals(others, everyone.drop(1).associateWith { (server, user) -> of(server, user) })
        assertEquals("the server's settings stay", 1, n("download_settings", "serverId = '$S1'"))
    }

    @Test fun removingAServerDeletesEveryRowForEveryAccountOnItAndItsDownloadSettings() = runBlocking {
        fill()
        val s2 = of(S2, U1)
        servers.removeServer(S1)
        assertEquals(SyncRows.TABLES.associateWith { 0 }, SyncRows.TABLES.associateWith { n(it, "serverId = '$S1'") })
        assertEquals(0, n("download_settings", "serverId = '$S1'"))
        assertEquals(s2, of(S2, U1))
        assertEquals(1, n("download_settings", "serverId = '$S2'"))
    }

    @Test fun deletingAnOutboxRowDeletesItsMarks() {
        fill()
        raw.execSQL("DELETE FROM sync_outbox WHERE serverId = ? AND userId = ?", arrayOf(S1, U1))
        assertEquals(0, n("sync_marks", "serverId = '$S1' AND userId = '$U1'"))
        assertEquals(4, n("sync_marks"))
        assertEquals("nothing else of that account goes", 1, n("sync_state", "serverId = '$S1' AND userId = '$U1'"))
    }

    @Test fun aMarkNeedsItsOutboxRowAndEveryOtherRowNeedsItsAccount() {
        val nobody = id("nobody")
        val failures = listOf(
            runCatching { SyncRows.mark(raw, 999, S1, U1, a) },
            runCatching { SyncRows.outbox(raw, S1, nobody, a) },
            runCatching { SyncRows.state(raw, S1, nobody) },
            runCatching { SyncRows.rejected(raw, S1, nobody) },
            runCatching { SyncRows.released(raw, S1, nobody, a, show) },
            runCatching { SyncRows.profileSettings(raw, S1, nobody) },
            runCatching { SyncRows.autoNext(raw, S1, nobody, show, a) },
        )
        assertEquals(List(failures.size) { true }, failures.map { it.isFailure })
    }

    @Test fun theNewColumnsHaveTheirDefaults() {
        raw.execSQL("INSERT INTO download_settings (serverId) VALUES (?)", arrayOf(S1))
        assertEquals(1, n("download_settings", "serverId = '$S1' AND shareBetweenProfiles = 0 AND deleteShared = 'ANYONE'"))
        raw.execSQL("INSERT INTO profile_download_settings (serverId, userId) VALUES (?, ?)", arrayOf(S1, U1))
        assertEquals(1, n("profile_download_settings", "afterFinish = 'ASK' AND autoNextCount = 0"))
        raw.execSQL("INSERT INTO auto_next_requests (serverId, userId, seriesId, anchorItemId, quality, requestedAt, blockedAt) VALUES (?, ?, ?, ?, 'LOW', 1, NULL)", arrayOf(S1, U1, show, a))
        assertEquals(1, n("auto_next_requests", "tries = 0"))
        raw.execSQL(
            "INSERT INTO sync_outbox (serverId, userId, itemId, itemKind, kind, flag, positionMs, occurredAt, refreshKeys, fromOfflinePlay, sentPlayCount) " +
                "VALUES (?, ?, ?, 'MOVIE', 'FAVOURITE', 1, NULL, 5, '', 0, NULL)",
            arrayOf(S1, U1, a),
        )
        assertEquals(1, n("sync_outbox", "sentOnce = 0 AND attempts = 0 AND serverErrors = 0 AND notBefore = 0"))
        raw.execSQL("INSERT INTO sync_marks (seq, serverId, userId, itemId) SELECT seq, serverId, userId, itemId FROM sync_outbox")
        assertEquals(1, n("sync_marks", "leftList = ''"))
    }

    @Test fun anOutboxSeqIsNeverReused() {
        val first = SyncRows.outbox(raw, S1, U1, a)
        val second = SyncRows.outbox(raw, S1, U1, a)
        raw.execSQL("DELETE FROM sync_outbox WHERE seq = ?", arrayOf(second))
        val third = SyncRows.outbox(raw, S1, U1, a)
        assertTrue("$first < $second < $third", first < second && second < third)
    }
}
