package app.shoalwatch.data.db

import app.shoalwatch.S1
import app.shoalwatch.S2
import app.shoalwatch.U1
import app.shoalwatch.U2
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The download tables' cascades (schema v4), through the real [app.shoalwatch.data.servers.ServerStore]: a server takes all
 * of its downloads with it; an account takes only its ownership and offline plays (who keeps a shared download is Task 12's
 * rule, not a cascade); deleting a download takes its owners, tracks and segments. Library rows are never touched.
 */
class DownloadCascadeTest : LibraryTestBase() {
    private val raw get() = db.openHelper.writableDatabase
    private val a = id("a")
    private val b = id("b")

    private fun fill() = runBlocking {
        for (s in listOf(s1u1, s1u2, s2u1)) {
            library.writePage(s, "resume", 0, listOf(row(item("a"), watched(played = true)), row(item("b"))), 2, t0, requestedAtSeq = 0)
        }
        DownloadRows.fullSet(raw, S1, U1, a)
        DownloadRows.owner(raw, S1, U2, a)
        DownloadRows.offlinePlay(raw, S1, U2, a)
        DownloadRows.download(raw, S1, b)
        DownloadRows.owner(raw, S1, U2, b)
        DownloadRows.settings(raw, S1)
        DownloadRows.fullSet(raw, S2, U1, a)
        DownloadRows.settings(raw, S2)
    }

    private fun n(table: String, where: String = "1=1") = DownloadRows.count(raw, table, where)

    private fun library() = listOf("items", "user_data", "list_entries", "list_state", "visible_items").associateWith { n(it) }

    @Test fun removingAnAccountDeletesOnlyItsOwnershipAndOfflinePlays() = runBlocking {
        fill()
        val before = DownloadRows.TABLES.associateWith { n(it) }
        val otherLibrary = listOf("user_data", "visible_items").associateWith { n(it, "userId = '$U2' OR serverId = '$S2'") }
        servers.removeAccount(S1, U1)
        assertEquals(0, n("download_owners", "serverId = '$S1' AND userId = '$U1'"))
        assertEquals(0, n("offline_plays", "serverId = '$S1' AND userId = '$U1'"))
        assertEquals(before["download_owners"]!! - 1, n("download_owners"))
        assertEquals(before["offline_plays"]!! - 1, n("offline_plays"))
        for (table in DownloadRows.TABLES - "download_owners" - "offline_plays") assertEquals(table, before[table], n(table))
        assertEquals("the downloads stay: the ownership rule decides (Task 12)", 2, n("downloads", "serverId = '$S1'"))
        assertEquals(1, n("download_owners", "serverId = '$S1' AND userId = '$U2' AND itemId = '$a'"))
        assertEquals("other users' and servers' library rows are untouched", otherLibrary, listOf("user_data", "visible_items").associateWith { n(it, "userId = '$U2' OR serverId = '$S2'") })
        assertEquals(4, n("items"))
    }

    @Test fun removingAServerDeletesEveryDownloadRowForItAndNothingElse() = runBlocking {
        fill()
        val s2 = DownloadRows.TABLES.associateWith { n(it, "serverId = '$S2'") }
        val s2Library = listOf("items", "user_data", "visible_items").associateWith { n(it, "serverId = '$S2'") }
        servers.removeServer(S1)
        assertEquals(DownloadRows.TABLES.associateWith { 0 }, DownloadRows.TABLES.associateWith { n(it, "serverId = '$S1'") })
        assertEquals(s2, DownloadRows.TABLES.associateWith { n(it, "serverId = '$S2'") })
        assertEquals(s2Library, listOf("items", "user_data", "visible_items").associateWith { n(it, "serverId = '$S2'") })
    }

    @Test fun deletingADownloadDeletesItsOwnersTracksAndSegmentsButNotItsArtworkOrPlays() = runBlocking {
        fill()
        val lib = library()
        raw.execSQL("DELETE FROM downloads WHERE serverId = ? AND itemId = ?", arrayOf(S1, a))
        for (table in listOf("download_owners", "download_tracks", "download_segments")) assertEquals(table, 0, n(table, "serverId = '$S1' AND itemId = '$a'"))
        assertEquals("artwork is pinned per item, not per download", 1, n("pinned_artwork", "serverId = '$S1' AND itemId = '$a'"))
        assertEquals("an offline play is the user's record, kept for Milestone 5", 2, n("offline_plays", "serverId = '$S1' AND itemId = '$a'"))
        assertEquals("the other server's download of the same item is untouched", 1, n("download_owners", "serverId = '$S2' AND itemId = '$a'"))
        assertEquals(1, n("download_owners", "serverId = '$S1' AND itemId = '$b'"))
        assertEquals(lib, library())
    }

    @Test fun aRejectedTokenKeepsEveryDownloadRow() = runBlocking {
        fill()
        val before = DownloadRows.TABLES.associateWith { n(it) }
        servers.invalidateToken(S1, U1)
        assertEquals(before, DownloadRows.TABLES.associateWith { n(it) })
    }

    @Test fun signingInAgainOverAnExistingAccountKeepsItsOwnershipAndPlays() = runBlocking {
        fill()
        val before = DownloadRows.TABLES.associateWith { n(it) }
        servers.upsertAccount(app.shoalwatch.data.servers.Account(S1, U1, "ann", "a-brand-new-token"), makeActive = true)
        assertEquals(before, DownloadRows.TABLES.associateWith { n(it) })
    }

    @Test fun anOwnerNeedsItsDownloadAndItsAccount() {
        DownloadRows.download(raw, S1, a)
        val noDownload = runCatching { DownloadRows.owner(raw, S1, U1, b) }
        val noAccount = runCatching { DownloadRows.owner(raw, S1, id("nobody"), a) }
        assertEquals(listOf(true, true), listOf(noDownload.isFailure, noAccount.isFailure))
    }

    @Test fun shareBetweenProfilesDefaultsToOff() {
        raw.execSQL("INSERT INTO download_settings (serverId) VALUES (?)", arrayOf(S1))
        assertEquals(1, n("download_settings", "serverId = '$S1' AND shareBetweenProfiles = 0"))
    }
}
