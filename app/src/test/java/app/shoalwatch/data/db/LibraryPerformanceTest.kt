package app.shoalwatch.data.db

import androidx.sqlite.db.SimpleSQLiteQuery
import app.shoalwatch.S1
import app.shoalwatch.U1
import app.shoalwatch.data.db.library.ItemDao
import app.shoalwatch.data.db.library.ListDao
import app.shoalwatch.data.library.ItemKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A sanity check that the main queries stay indexed at a realistic size (not a benchmark: it asserts shape, not time). */
class LibraryPerformanceTest : LibraryTestBase() {
    private fun plan(sql: String, vararg args: Any?): List<String> =
        db.openHelper.readableDatabase.query(SimpleSQLiteQuery("EXPLAIN QUERY PLAN $sql", arrayOf(*args))).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("detail"))) }
        }

    @Test fun aTwoHundredRowPageOfFiveThousandIsReadThroughTheJoin() = runBlocking {
        val rows = (1..5_000).map { row(item("p$it", name = "Film $it"), if (it % 2 == 0) watched(played = true) else null) }
        rows.chunked(500).forEachIndexed { i, chunk -> library.writePage(s1u1, "big", i * 500, chunk, 5_000, t0, requestedAtSeq = 0) }
        assertEquals(5_000, db.listDao().countEntries(S1, U1, "big"))
        val page = db.listDao().getList(S1, U1, "big", 200)
        assertEquals(200, page.size)
        assertEquals("Film 1", page.first().item.name)
        assertEquals("Film 200", page.last().item.name)
        assertEquals(100, page.count { it.userData?.played == true })
    }

    @Test fun theListQueryUsesIndexesAndNeverScansItems() = runBlocking {
        library.writePage(s1u1, "big", 0, (1..50).map { row(item("p$it")) }, 50, t0, requestedAtSeq = 0)
        val listPlan = plan(ListDao.LIST_SQL.replace(Regex(":\\w+"), "?"), S1, U1, "big", 200)
        // Named parameters become one slot each in order of appearance in the original; the rewritten SQL repeats none here.
        File("build/query-plan-list.txt").writeText("LIST\n${listPlan.joinToString("\n")}\n")
        assertTrue(listPlan.toString(), listPlan.none { it.startsWith("SCAN items") || it.startsWith("SCAN i") })
        assertTrue(listPlan.toString(), listPlan.any { it.contains("list_entries") && it.contains("SEARCH") && it.contains("USING") })
        assertTrue(listPlan.toString(), listPlan.any { it.contains("items") && it.contains("SEARCH") && it.contains("USING") })
        assertFalse("ordered by the key, no sort needed: $listPlan", listPlan.any { it.contains("TEMP B-TREE") })
    }

    @Test fun theOfflineSeriesEpisodesQueryUsesTheDownloadsSeriesIndexAndThePrimaryKeys() = runBlocking {
        val items = (1..5_000).map { n -> if (n <= 500) episode("e$n", 1 + n / 50, n % 50, series = "show${n % 10}") else item("p$n") }
        items.chunked(500).forEach { library.writeItems(s1u1, it.map { i -> row(i) }, t0, requestedAtSeq = 0) }
        val store = app.shoalwatch.data.db.downloads.DownloadStore(db, { 1L })
        store.add(s1u1, items.take(500).map {
            app.shoalwatch.data.db.downloads.DownloadStore.NewDownload(it.id, it.kind, it.seriesId, it.seasonId, app.shoalwatch.data.downloads.DownloadQuality.ORIGINAL,
                app.shoalwatch.data.downloads.StorageKind.INTERNAL, null, null, null)
        })
        assertEquals(500, count("downloads"))
        val sql = app.shoalwatch.data.db.downloads.OfflineLibraryDao.SERIES_EPISODES_SQL
            .replace(":serverId", "'$S1'").replace(":userId", "'$U1'").replace(":seriesId", "'${id("show3")}'")
        val p = plan(sql)
        File("build/query-plan-offline-episodes.txt").writeText("OFFLINE SERIES EPISODES\n${p.joinToString("\n")}\n")
        assertTrue(p.toString(), p.none { it.startsWith("SCAN i") || it.startsWith("SCAN items") || it.startsWith("SCAN d") })
        assertTrue(p.toString(), p.any { it.startsWith("SEARCH d USING INDEX index_downloads_serverId_seriesId") })
        assertTrue(p.toString(), p.any { it.startsWith("SEARCH i USING INDEX sqlite_autoindex_items_1") })
        assertTrue(p.toString(), p.any { it.startsWith("SEARCH v USING") && it.contains("sqlite_autoindex_visible_items_1") })
    }

    @Test fun theEpisodesQueryUsesTheSeriesIndex() = runBlocking {
        val episodes = (1..30).map { episode("e$it", 1 + it / 10, it % 10) }
        library.writeItems(s1u1, episodes.map { row(it) }, t0, requestedAtSeq = 0)
        val everySeason = plan(ItemDao.EPISODES_SQL, U1, S1, id("show"), null)
        val oneSeason = plan(ItemDao.EPISODES_SQL, U1, S1, id("show"), id("season1"))
        File("build/query-plan-episodes.txt").writeText("EPISODES (all seasons)\n${everySeason.joinToString("\n")}\nEPISODES (one season)\n${oneSeason.joinToString("\n")}\n")
        for (p in listOf(everySeason, oneSeason)) {
            assertTrue(p.toString(), p.none { it.startsWith("SCAN i") })
            assertTrue(p.toString(), p.any { it.contains("SEARCH i USING INDEX index_items_serverId_seriesId") })
        }
        assertEquals(30, db.itemDao().getEpisodes(S1, U1, id("show"), null).size)
        assertTrue(db.itemDao().getEpisodes(S1, U1, id("show"), null).all { it.item.kind == ItemKind.EPISODE })
    }
}
